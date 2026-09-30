# Hot Reload 프레임워크 프로젝트 생성 지시서

> 초기 설계 기록이다. 현재의 업무 코드 선택적 재로딩·인프라 변경 시 JVM 재시작 정책은 [hybrid-reload-spec.md](hybrid-reload-spec.md)를 따른다.

## 목표

Spring Boot 3.5.16 / Gradle 멀티 모듈 프로젝트를 만든다. 구조는 다음과 같다.

- **부모 context**(host)는 JVM 수명 동안 한 번만 뜨는 Spring Boot 애플리케이션이다. 내장 웹 서버(Tomcat)와 공용 인프라 bean을 가진다.
- **자식 context**(application)는 애플리케이션 클래스패스를 새 클래스로더로 로드해서 만든다. 애플리케이션 클래스가 바뀌면 새 자식 context를 만들고 기존 자식 context를 폐기한다.
- 웹 서버는 재시작하지 않는다. 요청은 부모의 delegating servlet을 거쳐 현재 세대(generation)의 자식 `DispatcherServlet`으로 전달된다.
- 재로딩 흐름은 직접 제어한다. `RestartClassLoader`와 `FileSystemWatcher` 계열 클래스만 spring-boot-devtools에서 소스로 가져와 사용한다. devtools는 의존성으로 추가하지 않는다.

## 기술 조건

- Java 17 (toolchain)
- Gradle wrapper 8.14 이상, Groovy DSL
- Spring Boot Gradle plugin `3.5.16`, `io.spring.dependency-management` `1.1.7`
- 루트 패키지: `com.example.reload` (host, reload, api), `com.example.app` (sample-app)
- `spring-boot-devtools` 의존성 **금지**

## 모듈 구조

```
settings.gradle            include 'api', 'reload', 'host', 'sample-app'
api/          java-library   부모·자식이 공유하는 타입 (인터페이스, DTO). 부모 클래스로더에서만 로드됨
reload/       java-library   devtools에서 가져온 클래스 + 세대 관리 + delegating servlet
host/         boot app       부모 context. api, reload에 의존. sample-app에는 의존하지 않음
sample-app/   java           재로딩 대상 애플리케이션 코드. host 런타임 클래스패스에 절대 포함되면 안 됨
```

의존성 규칙:

- `host`: `implementation project(':api')`, `implementation project(':reload')`, `spring-boot-starter-web`
- `reload`: `api project(':api')`, `compileOnly`/`implementation`으로 `spring-webmvc`, `spring-boot`, `spring-boot-autoconfigure` (BOM은 `platform('org.springframework.boot:spring-boot-dependencies:3.5.16')`)
- `sample-app`: `compileOnly project(':api')`, `compileOnly 'org.springframework:spring-webmvc'`, `compileOnly 'org.springframework.boot:spring-boot'`. 실행 시 이 클래스들은 부모 클래스로더가 제공한다.
- 이번 버전에서는 `sample-app`의 서드파티 의존성도 host 클래스패스에 있어야 한다는 제약을 둔다. README에 명시할 것.

## 1. devtools 소스 가져오기 (`reload` 모듈)

spring-boot 저장소의 `v3.5.16` 태그(없으면 `3.5.x` 브랜치)에서 아래 클래스를 복사한다. 경로는 `spring-boot-project/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/`이다.

- `restart/classloader/`: `RestartClassLoader`, `ClassLoaderFile`, `ClassLoaderFiles`, `ClassLoaderFileRepository`, `ClassLoaderFileURLStreamHandler`
- `filewatch/`: `FileSystemWatcher`, `FileChangeListener`, `ChangedFile`, `ChangedFiles`, `DirectorySnapshot`, `FileSnapshot`, `SnapshotStateRepository`
- `classpath/PatternClassPathRestartStrategy`, `classpath/ClassPathRestartStrategy`

규칙:

- 패키지를 `com.example.reload.devtools.*`로 바꾼다.
- Apache 2.0 라이선스 헤더를 유지하고, 각 파일 상단에 원본 경로와 버전을 주석으로 남긴다. `reload/NOTICE` 파일에 출처를 기록한다.
- 복사한 클래스 외의 devtools 클래스(Restarter, RestartApplicationListener, 자동 구성 등)에 대한 참조는 제거한다. 로직은 가능한 한 원본 그대로 둔다.
- 네트워크 접근이 안 되면 작업을 멈추고 사용자에게 알린다. 추측으로 재작성하지 않는다.

## 2. 설정 프로퍼티

`@ConfigurationProperties("reload")` → `ReloadProperties`

```yaml
reload:
  classpath:                 # 자식 클래스로더 URL (디렉터리 또는 jar)
    - ../sample-app/build/classes/java/main
    - ../sample-app/build/resources/main
  base-packages: [com.example.app]
  poll-interval: 1s
  quiet-period: 400ms
  drain-timeout: 30s
  exclude-patterns: ["**/*.log"]   # 변경으로 치지 않을 패턴
```

상대 경로는 host 작업 디렉터리 기준으로 해석한다. `host/src/main/resources/application.yml`에 위 기본값을 넣고, `bootRun`의 `workingDir`을 host 모듈 디렉터리로 둔다.

## 3. 핵심 설계 (`reload` 모듈)

### 3.1 Generation

```java
final class Generation {
    int id;
    RestartClassLoader classLoader;
    AnnotationConfigWebApplicationContext context;
    DispatcherServlet dispatcher;
    AtomicInteger inFlight;
    volatile boolean retired;
}
```

- `acquire()`: `retired`가 아니면 `inFlight`를 증가시키고 true를 반환한다.
- `release()`: `inFlight`를 감소시키고, `retired` 상태에서 0이 되면 `dispose()`를 호출한다.
- `dispose()`: 한 번만 실행되도록 보장한다. 순서는 `dispatcher.destroy()` → `context.close()` → 캐시 정리(3.5) → `classLoader`에 대한 참조 해제.

### 3.2 GenerationManager (부모 context의 bean)

- `AtomicReference<Generation> current`
- `reload()`는 `synchronized`로 동시 실행을 막는다.
  1. `URL[]` 구성: `ReloadProperties.classpath`
  2. `new RestartClassLoader(hostClassLoader, urls)` — child-first이므로 `api` 등 공유 타입이 이 URL에 포함되면 안 된다. 시작할 때 URL 안에 `com/example/reload/**`나 api 패키지 클래스가 있으면 경고 로그를 남긴다.
  3. 자식 context 생성:
     - `AnnotationConfigWebApplicationContext`
     - `setParent(parentContext)`, `setClassLoader(loader)`, `setServletContext(parentServletContext)`
     - `register(ChildWebMvcConfig.class)`, `scan(basePackages)`
     - refresh하는 동안 TCCL을 `loader`로 바꿨다가 finally에서 원래대로 복구한다.
     - `refresh()`
  4. `DispatcherServlet` 생성: `new DispatcherServlet(childContext)`, `setPublishContext(false)`(세대마다 ServletContext attribute가 쌓이는 누수 방지), 합성 `ServletConfig`(이름 `dispatcher-gen-{id}`, 부모 ServletContext 사용)로 `init()`.
  5. 3~4 중 하나라도 실패하면 새 세대를 dispose하고 기존 세대를 유지한다. 실패 원인은 ERROR 로그로 남긴다.
  6. 성공하면 `current.getAndSet(newGen)`으로 교체하고, 이전 세대를 `retired = true`로 표시한다. `inFlight == 0`이면 즉시 dispose하고, 아니면 `drain-timeout` 뒤에 강제 dispose하도록 예약한다.
- 부모 context의 `ApplicationReadyEvent`에서 첫 세대를 로드한다.
- 부모가 종료될 때(`@PreDestroy`) watcher를 멈추고 현재 세대를 dispose한다.

### 3.3 ReloadingDispatcherServlet (부모에 등록)

- `HttpServlet`을 상속하고 `service()`를 오버라이드한다.
  - `Generation g = manager.current()`; `g == null || !g.acquire()`이면 한 번 재시도하고, 그래도 실패하면 503 + `Retry-After: 1`
  - `try { g.dispatcher().service(req, res); } finally { g.release(); }`
- 요청을 처리하는 동안 TCCL을 해당 세대의 클래스로더로 바꾸고 finally에서 복구한다.
- 비동기 요청(`request.isAsyncStarted()`)이면 release를 `AsyncListener` 완료 시점으로 미룬다.

### 3.4 부모 쪽 웹 구성 (host)

- `DispatcherServletAutoConfiguration`, `WebMvcAutoConfiguration`은 exclude한다. 부모에는 MVC가 없다.
- `ServletRegistrationBean<ReloadingDispatcherServlet>`를 `/`에 매핑하고 `loadOnStartup = 1`로 둔다.
- `ErrorMvcAutoConfiguration`도 exclude한다. 에러 처리는 자식 MVC가 담당한다.
- Jackson `ObjectMapper`는 부모 자동 구성에 그대로 둔다.

### 3.5 ChildWebMvcConfig (`reload` 모듈에 두고 자식에 register)

- `@EnableWebMvc`
- `WebMvcConfigurer.configureMessageConverters`에서 부모의 `ObjectMapper`로 `MappingJackson2HttpMessageConverter`를 구성한다.
- `@EnableConfigurationProperties`를 붙여 자식에서도 `@ConfigurationProperties`를 쓸 수 있게 한다. 환경은 `setParent` 시 부모 Environment가 merge된다.

### 3.6 캐시 정리 (세대 dispose 후)

devtools `Restarter.cleanupKnownCaches()`의 로직을 옮기되, 해당 세대의 클래스로더에 속한 항목만 제거한다.

- `Introspector.flushCaches()`, `ResolvableType.clearCache()`, `ReflectionUtils.clearCache()`, `AnnotationUtils.clearCache()`
- `CachedIntrospectionResults.clearClassLoader(loader)`
- 부모 `ObjectMapper.getTypeFactory().clearCache()`

### 3.7 변경 감지

- `FileSystemWatcher(daemon=true, pollInterval, quietPeriod)`로 `reload.classpath`의 디렉터리를 감시한다.
- `PatternClassPathRestartStrategy(excludePatterns)`로 걸러낸 뒤 변경이 남아 있으면 `manager.reload()`를 호출한다.
- 이 작업은 watcher 스레드에서 수행한다. 이 스레드는 부모 클래스로더를 TCCL로 갖는 스레드여야 한다.

## 4. sample-app

- `com.example.app.HelloController`: `GET /hello` → `{"message": "v1", "loader": "<클래스로더 identity>", "sharedBean": "<부모 bean identityHashCode>"}`
- `api` 모듈의 `GreetingService` 인터페이스를 host가 구현(부모 bean)하고, sample-app 컨트롤러가 주입받아 사용한다. 부모 bean 공유를 확인하기 위한 것이다.
- `GET /slow?ms=3000` 엔드포인트: drain 동작 확인용

## 5. 실행 방법 (README.md에 기재)

```bash
./gradlew :sample-app:classes          # 최초 1회
./gradlew :host:bootRun                # 터미널 1
./gradlew :sample-app:classes --continuous   # 터미널 2 (소스 저장 시 자동 컴파일)
```

`host`의 `bootRun`은 `dependsOn(':sample-app:classes')`로 둔다. sample-app은 런타임 클래스패스에 넣지 않는다.

## 6. 테스트 (reload 모듈)

`javax.tools.JavaCompiler`로 임시 디렉터리에 컨트롤러 v1/v2를 컴파일하는 통합 테스트를 작성한다. 부모는 `@SpringBootTest(webEnvironment = RANDOM_PORT)`로 띄운 host 구성이나 테스트 전용 구성을 사용한다.

1. **교체**: v1 응답 확인 → v2로 덮어쓰기 → 수 초 내에 v2 응답, 서버 포트가 동일하고 Tomcat이 재시작되지 않음
2. **부모 공유**: 세대가 바뀌어도 `sharedBean` identity는 같고 `loader` identity는 달라짐
3. **실패 격리**: refresh에 실패하는 v3(예: 생성자에서 예외)를 배포해도 v2가 계속 응답함
4. **drain**: `/slow` 요청 도중 교체해도 요청이 정상 완료되고, 그 뒤에 이전 세대가 close됨
5. **누수**: 교체를 20회 반복한 뒤 이전 클래스로더들에 대한 `WeakReference`가 `System.gc()` 반복 후 모두 비워짐 (타임아웃 10초)

## 7. 완료 기준

- `./gradlew build`가 통과하고 6번의 테스트가 모두 성공한다.
- 5번 절차로 수동 실행했을 때 `/hello` 수정이 Tomcat 재시작 없이 반영된다.
- `README.md`에 구조, 실행 방법, 제약(서드파티 의존성은 부모 클래스로더, 공유 타입은 `api`에만 둘 것, 부모 bean은 자식 객체를 붙잡지 말 것)을 적는다.

## 범위 밖 (이번에 하지 않음)

- 운영 환경의 fat jar에서 재로딩하기
- sample-app 전용 서드파티 jar를 자식 클래스로더에 추가하는 기능
- 원격 재로딩(ClassLoaderFiles 업로드)
- 자식 context에서의 Spring Boot auto-configuration 적용
