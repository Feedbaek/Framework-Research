# hot-reload

Tomcat을 재시작하지 않고 애플리케이션 코드만 새 클래스로더로 다시 올리는 Spring Boot 3.5.16 기반 재로딩 엔진과, 그 엔진을 쓰는 소비자 샘플.

```
reload/                 엔진 (라이브러리). 의존성으로 추가하면 auto-configuration으로 동작
sample-apps/
  greeting/             소비자 샘플 (평범한 Spring Boot 애플리케이션)
```

## 동작 방식

- **부모 context**: 소비자 애플리케이션 자체. 내장 Tomcat, 공용 bean, 부모 소유 클래스를 가진다. MVC는 없다.
- **자식 context (세대)**: 애플리케이션 출력 디렉터리를 새 클래스로더로 로드해서 만든 context + `DispatcherServlet`. 클래스가 바뀌면 새 세대를 만들어 교체하고 이전 세대는 폐기한다.
- 요청은 엔진이 `/`에 등록한 `ReloadingDispatcherServlet`을 거쳐 현재 세대로 간다. 새 세대가 뜨지 못하면 이전 세대가 계속 응답하고, 교체 중 진행 중이던 요청은 이전 세대에서 끝까지 처리된다(drain).
- `RestartClassLoader`와 `FileSystemWatcher` 계열만 spring-boot-devtools v3.5.16에서 소스로 복사해 쓴다(`reload/NOTICE`). devtools 의존성은 없다.

### 클래스 소유권: 기본은 자식, 지정한 것만 부모

| 소유 | 대상 | 로드 | 등록되는 context |
|---|---|---|---|
| 부모 | `reload.parent-packages`에 지정한 패키지 | 부모 클래스로더 (JVM 수명 동안 1번) | 부모 |
| 부모 | `@SpringBootApplication`(`@SpringBootConfiguration`) 클래스와 그 중첩 클래스 | 부모 | 부모 |
| 자식 | 그 밖의 애플리케이션 클래스 | 세대마다 새 클래스로더 | 자식 |

- 부모와 자식이 같은 출력 디렉터리(`build/classes/java/main`)를 본다. 세대별 클래스로더(`GenerationClassLoader`)는 기본적으로 child-first이고, 부모 소유 클래스만 부모에 먼저 위임한다. 그래서 공유 타입은 부모와 자식이 같은 `Class`를 본다.
- 컴포넌트 스캔은 `ReloadLayout.isChildComponent` 하나로 나눈다. 자식 클래스패스 디렉터리 안에 있고, 부모 소유가 아니고, `@SpringBootApplication` 클래스가 아닌 클래스만 자식이 등록하고, 부모 스캔은 그 클래스를 제외한다(`TypeExcludeFilter`).
- 엔진은 jar로 부모 클래스패스에만 둔다. 자식 클래스패스에 없으므로 child-first 로딩에서도 부모에서 로드된다. `reload.classpath`에 엔진 jar나 엔진 클래스가 든 디렉터리를 넣으면 엔진 타입이 세대마다 따로 로드되어 주입·캐스팅이 실패한다. 시작할 때 자식 클래스패스에서 엔진 클래스가 보이면 경고한다.
- 라이브러리 jar 등 자식 클래스패스 밖의 클래스는 스캔 패키지가 겹쳐도(예: 사내 공통 라이브러리 `com.example.*`) 부모에만 등록된다. 그 jar의 `@AutoConfiguration`과 Spring Boot 기본 auto-configuration도 부모에만 있다.
- 부모 소유 클래스 파일이 바뀌면 재로딩하지 않고 "재시작 필요" 경고를 남긴다.
- 시작할 때 부모 소유 클래스가 자식 소유 클래스를 참조하는지 검사해 경고한다(아래 제약 참고).

### 자식 AOP 인프라

자식에는 Spring Boot auto-configuration이 없다. 엔진이 `ChildInfrastructureConfiguration`으로 필요한 것만 켠다.

| 기능 | 자식에서 |
|---|---|
| auto-proxy | AspectJ가 있으면 `ChildAspectJAutoProxyCreator`. 클래스 프록시 기본(`spring.aop.auto`, `spring.aop.proxy-target-class` 존중) |
| 부모의 `@Aspect` bean | 자식 bean에도 적용된다 |
| 부모의 `Advisor` bean | **적용하지 않는다** (아래 설명) |
| `@Transactional` | 부모에 `TransactionManager`가 있으면 자식에 `@EnableTransactionManagement`. 트랜잭션 매니저는 부모 것 |
| `@Cacheable` 등 | 부모에 `@EnableCaching`이 있으면 자식에도 `@EnableCaching`. `CacheManager`는 부모 것 |
| `@Async`, `@Scheduled`, 메서드 검증 | 켜지 않는다. 필요하면 자식 패키지의 설정 클래스에 `@Enable*`을 선언한다 |

부모의 `Advisor` bean(트랜잭션·캐시 advisor, 사용자 정의 advisor)을 자식 bean에 적용하면 메서드별 메타데이터 캐시가 부모 쪽 인스턴스에 쌓여 이전 세대의 클래스로더가 수거되지 않는다(테스트로 확인). 그래서 자식은 자기 context에 정의된 advisor만 쓴다. 애플리케이션이 자식에 `@EnableAspectJAutoProxy`를 직접 선언해도 엔진의 creator로 바뀐다.

### 설정 배치 규칙

**인프라 설정은 부모에 두고, Advisor·BeanPostProcessor로 동작하는 `@Enable*`만 자식에 둔다.**

| 부모 (애플리케이션 클래스 또는 `reload.parent-packages`) | 자식 (재로딩 대상 패키지의 설정 클래스) |
|---|---|
| `@EnableCaching`, `TransactionManager`, `DataSource`, `CacheManager` (엔진이 자식에 트랜잭션·캐시를 다시 켠다) | `@EnableAsync`, `@EnableScheduling`, `@EnableConfigurationProperties` |
| Boot customizer (`Jackson2ObjectMapperBuilderCustomizer`, `WebServerFactoryCustomizer` 등) | `@EnableMethodSecurity`, `@EnableRetry` |
| `Filter`, `FilterRegistrationBean` 등 서블릿 컴포넌트, `SecurityFilterChain` | `@EnableKafka`, `@EnableRabbit`, `@EnableJms` |
| 공통 관심사 `@Aspect`, 인프라 `@Bean` (클라이언트, 공용 bean) | `Advisor`·`BeanPostProcessor` bean (`@Bean` 또는 `@Component`) |

이유: 자식의 인프라 설정은 부모 auto-configuration과 서블릿 컨테이너가 보지 못하고, 부모의 Advisor·BeanPostProcessor는 자식 bean을 처리하지 않는다. 어기면 오류 없이 기능이 빠진다(`docs/reload-aop-proxy-risks.md` 8~10장).

엔진이 이 규칙을 검사해 경고한다(`ConfigurationPlacementChecker`, `reload.placement-check.enabled=false`로 끈다).

- **부모 쪽** (첫 세대를 만들 때 한 번): 부모에 선언되어 자식 bean에는 적용되지 않는 `@EnableAsync`, `@EnableScheduling`, `Advisor` bean. 엔진이 자식에 다시 켜는 트랜잭션·캐시 advisor는 제외한다.
- **자식 쪽** (위반 목록이 바뀐 세대마다): 자식 `@Configuration`의 허용되지 않은 `@Enable*`·`@Import`, Advisor·BeanPostProcessor가 아닌 `@Bean` 메서드, 자식에 정의된 인프라 bean(Filter, 서블릿 리스너·초기화 bean, Boot customizer, `SecurityFilterChain`, `CacheManager`, `TransactionManager`, `DataSource`, runner, `HttpMessageConverter`).

```
WARN ... Configuration placement rule: infrastructure configuration belongs in the parent; ... Violations in generation 1:
  com.example.app.config.AppConfig @EnableTransactionManagement: define the TransactionManager in the parent; ...
  com.example.app.config.AppConfig#indent() [Jackson2ObjectMapperBuilderCustomizer] is not applied to the parent ObjectMapper ...
  com.example.app.web.HeaderFilter [Filter] is not registered with the servlet container ...
```

사내 라이브러리의 Advisor·BeanPostProcessor 기반 `@Enable*`은 `reload.placement-check.allowed-child-annotations`에 추가한다. `WebMvcConfigurer`를 구현한 자식 설정(인터셉터, CORS 등)은 `@Bean` 메서드나 `@Enable*`이 없으면 경고하지 않는다. 부모에는 MVC가 없고 자식 MVC가 부모·자식의 `WebMvcConfigurer`를 모두 적용하므로 자식에 둬도 동작한다.

## 엔진 (`reload`)

```groovy
dependencies {
    implementation project(':reload')          // 또는 'com.example:reload:0.0.1-SNAPSHOT'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

- `ReloadAutoConfiguration`(auto-configuration)이 엔진 bean과 `ReloadingDispatcherServlet`을 등록한다. 서블릿 웹 애플리케이션에서만 동작한다.
- `ReloadApplicationListener`(`spring.factories`)가 부모 refresh 전에 배치(`ReloadLayout`)를 정하고 부모 스캔 필터를 등록한다.
- `ReloadAutoConfigurationImportFilter`가 부모의 `DispatcherServletAutoConfiguration`, `WebMvcAutoConfiguration`, `ErrorMvcAutoConfiguration`을 제외한다.
- `reload.enabled=false`이면 모두 꺼지고 평범한 Spring MVC 애플리케이션으로 동작한다.
- `./gradlew :reload:publishToMavenLocal`로 로컬 Maven 저장소에 배포할 수 있다.

### 설정

```yaml
reload:
  enabled: true                    # false면 엔진을 끄고 일반 MVC 애플리케이션으로 동작
  parent-packages: []              # 부모 소유 패키지 (공유 타입, 부모 bean)
  classpath: []                    # 비우면 @SpringBootApplication 클래스의 출력 디렉터리
  base-packages: []                # 비우면 @SpringBootApplication 클래스의 패키지
  trigger:
    mode: watch                    # watch | api | both
    api-path: /_reload             # mode가 api/both일 때 등록되는 재로딩 API 경로
  poll-interval: 1s                # 이하 watch 방식 설정
  quiet-period: 400ms              # poll-interval보다 짧아야 한다
  exclude-patterns: ["**/*.log"]   # 변경으로 치지 않을 패턴
  drain-timeout: 30s
  placement-check:
    enabled: true                  # 설정 배치 규칙 위반 경고 (위 "설정 배치 규칙")
    allowed-child-annotations: []  # 자식 설정에 추가로 허용할 Advisor·BeanPostProcessor 기반 @Enable*
```

`classpath`의 jar 항목은 로드는 되지만 감시하지 않는다(디렉터리만 감시).

### 재로딩 트리거

| `reload.trigger.mode` | 재로딩 시점 |
|---|---|
| `watch` (기본) | 클래스패스 디렉터리의 변경을 감지했을 때 (`poll-interval`, `quiet-period`, `exclude-patterns`) |
| `api` | 재로딩 API가 호출됐을 때만. 파일이 바뀌어도 재로딩하지 않는다 |
| `both` | 둘 다 |

재로딩 API(`api`, `both`일 때 부모에 별도 servlet으로 등록):

```bash
curl -X POST localhost:8080/_reload
# 200 {"reloaded":true,"generation":2,"previousGeneration":1,"durationMillis":19,"failedReloads":0,"error":null}
# 500 {"reloaded":false,"generation":2,"previousGeneration":2,...,"error":"java.lang.IllegalStateException: ..."}

curl localhost:8080/_reload
# 200 {"mode":"api","generation":2,"failedReloads":0}
```

- 새 세대가 뜨지 못하면 500과 가장 안쪽 원인을 돌려주고 기존 세대가 계속 응답한다.
- 세대를 거치지 않는 servlet이라 자식 세대가 없거나 실패한 상태에서도 호출할 수 있다. 재로딩은 동시에 하나만 실행된다.
- **인증이 없다.** 개발 환경에서만 켠다. 기본값(`watch`)에서는 등록되지 않는다.
- 코드에서는 `GenerationManager.reloadWithResult()`(결과 반환) 또는 `reload()`(성공 여부)를 호출하면 된다.

`watch` 방식은 빌드 도구가 클래스 파일을 지우고 다시 쓰는 사이가 `quiet-period`보다 길면 재로딩이 두 번 일어날 수 있다(중간 세대는 일부 클래스가 빠진 상태). Gradle 증분 컴파일에서 실제로 관찰됐다. 빌드가 끝난 뒤 API를 한 번 호출하는 `api` 방식은 이 문제가 없다.

패키지 구조 (`com.example.reload`, 의존 방향: `autoconfigure` → `generation` → `watch`/`layout`/`child`):

```
com.example.reload
├── ReloadProperties   엔진 전체 설정 (reload.*)
├── autoconfigure/     부모 context 부트스트랩 (Spring Boot 연동)
├── generation/        세대 생명주기와 요청 라우팅 (엔진 핵심, 공개 API GenerationManager)
├── child/             모든 자식 세대 context에 register되는 인프라
├── layout/            자식 클래스패스 배치와 부모/자식 소유권 경계
├── watch/             클래스패스 변경 감지
└── devtools/          Spring Boot DevTools 복사본 (NOTICE 참고)
```

주요 클래스:

| 패키지 | 클래스 | 역할 |
|---|---|---|
| (루트) | `ReloadProperties` | `reload.*` 설정 |
| `autoconfigure` | `ReloadAutoConfiguration`, `ReloadAutoConfigurationImportFilter` | 엔진 bean과 servlet 등록, 부모 MVC auto-configuration 제외 |
| `autoconfigure` | `ReloadApplicationListener` | 부모 refresh 전 배치 결정, 부모 스캔 필터 등록 |
| `generation` | `GenerationManager` | 감시 시작 + 첫 세대 로드, `reload()`/`reloadWithResult()`로 교체, 실패 시 기존 세대 유지, 경계 검사 |
| `generation` | `Generation` | 클래스로더·context·dispatcher 한 벌. 진행 중 요청 수를 세고 retire 뒤 dispose |
| `generation` | `GenerationClassLoader` | `RestartClassLoader` + 부모 소유 클래스 parent-first 위임 |
| `generation` | `GenerationApplicationContext` | 자식 context. 스캔에서 부모 소유·애플리케이션 클래스 제외 |
| `generation` | `GenerationCacheCleaner` | dispose 뒤 Introspector/Spring/Jackson 캐시와 공개 API가 없는 Spring·Spring Security 내부 정적 캐시 정리 |
| `generation` | `ReloadingDispatcherServlet` | 요청마다 현재 세대를 acquire하고 위임. 세대가 없으면 `503` + `Retry-After: 1` |
| `generation` | `ReloadTriggerServlet`, `ReloadResult` | 재로딩 API (`POST`: 재로딩, `GET`: 상태), 재로딩 결과 |
| `child` | `ChildInfrastructureConfiguration`, `ChildAspectJAutoProxyCreator` | 자식 AOP·트랜잭션·캐시 인프라, 부모 `Advisor` bean 차단 |
| `child` | `ChildWebMvcConfig` | 자식 `@EnableWebMvc` 구성. 부모 `ObjectMapper`의 복사본으로 Jackson 컨버터 구성 |
| `layout` | `ReloadLayout`, `ClassOwnership` | 자식 클래스패스와 클래스 소유권 결정 |
| `layout` | `ReloadParentTypeExcludeFilter` | 부모 스캔에서 자식 소유 클래스 제외 |
| `layout` | `BoundaryChecker` | 부모 소유 클래스 → 자식 소유 클래스 참조 검사 |
| `layout` | `ConfigurationPlacementChecker` | 설정 배치 규칙 검사 (부모에만 있는 Advisor·BPP 기능, 자식의 인프라 설정) |
| `watch` | `ClassPathChangeWatcher` | 변경 감지. 자식 소유 변경은 재로딩, 부모 소유 변경은 재시작 경고 |

## 소비자 샘플 (`sample-apps/greeting`)

```
src/main/java/com/example/greeting/
  GreetingApplication.java        @SpringBootApplication (부모 소유)
  shared/GreetingService.java     공유 타입   (부모 소유: reload.parent-packages)
  shared/DefaultGreetingService   부모 bean   (부모 소유)
  web/HelloController.java        재로딩 대상 (자식 소유: 기본값)
```

```yaml
reload:
  parent-packages: [com.example.greeting.shared]
```

### 실행

파일 감시 방식(`watch`, 샘플 기본값):

```bash
./gradlew :sample-apps:greeting:bootRun                     # 터미널 1
./gradlew :sample-apps:greeting:classes --continuous        # 터미널 2 (소스 저장 시 자동 컴파일 → 감지)
```

API 방식(`api`): 컴파일이 끝난 뒤 `triggerReload` 태스크가 `POST /_reload`를 한 번 호출한다.

```bash
./gradlew :sample-apps:greeting:bootRun --args='--reload.trigger.mode=api'   # 터미널 1
./gradlew :sample-apps:greeting:triggerReload --continuous                   # 터미널 2 (저장 → 컴파일 → 재로딩)
```

포트나 경로가 다르면 `-PreloadUrl=http://localhost:9090/_reload`로 지정한다.

```bash
curl localhost:8080/hello
# {"message":"v1","greeting":"Hello, world!","loader":"GenerationClassLoader@7a729f84","sharedBean":"5c250074"}
```

- `web/HelloController.java`의 `"v1"`을 바꾸고 저장하면 새 세대가 올라온다. `loader`는 바뀌고 `sharedBean`은 그대로다.
- `shared/` 아래 클래스를 바꾸면 재로딩되지 않고 `Parent-owned classes changed; restart the application to apply them` 경고가 나온다.
- `GET /slow?ms=3000`을 보내 놓고 그 사이에 코드를 바꾸면 진행 중 요청은 이전 세대에서 끝까지 처리된다.

## 제약

- **공유 타입과 부모 bean은 `reload.parent-packages`에 둔다.** 부모와 자식이 주고받는 타입(인터페이스, DTO)을 자식 패키지에 두면 부모와 자식이 서로 다른 `Class`를 보게 되어 주입·캐스팅이 실패한다.
- **부모 소유 클래스는 자식 소유 클래스를 참조하면 안 된다.** 같은 소스 세트라 컴파일러가 막지 못한다. 참조하면 부모가 그 클래스를 따로 로드해서 타입이 어긋나고 재로딩도 되지 않는다. 엔진이 시작할 때 클래스 파일을 검사해 경고한다.
  ```
  WARN ... Parent-owned classes reference reloadable (child-owned) classes. ...
    com.example.greeting.GreetingApplication -> [com.example.greeting.web.HelloController]
  ```
  `@SpringBootApplication` 클래스에 자식 타입을 반환하는 `@Bean` 메서드를 두는 것도 여기에 해당한다.
- **서드파티 의존성은 부모 클래스로더에 있다.** 자식 클래스로더에 전용 jar를 올리는 기능은 없다.
- **부모 bean은 자식 객체를 붙잡지 않는다.** 부모 bean의 필드·캐시·리스너 목록에 자식 클래스의 인스턴스, `Class`, 람다를 저장하면 이전 세대가 수거되지 않는다. 부모 `CacheManager`에 자식 클래스 인스턴스를 값으로 캐시하는 것도 같다(검증하지 않음).
- **부모의 `Advisor` bean은 자식에 적용되지 않는다.** 자식 bean에 적용할 공통 관심사는 `@Aspect`로 만들거나 자식 패키지에 둔다.
- **인프라 설정은 부모에, Advisor·BeanPostProcessor 기반 `@Enable*`만 자식에 둔다.** 위 "설정 배치 규칙" 참고. 엔진이 시작할 때와 세대가 뜰 때 위반을 경고한다.
- **JPA 엔티티·Spring Data 리포지토리**는 부모의 `EntityManagerFactory`가 다루므로 부모 소유 패키지에 두어야 하고 재로딩되지 않는다(이 샘플에는 없음, 검증하지 않음).
- 자식 context에는 Spring Boot auto-configuration이 적용되지 않는다. 환경(프로퍼티)은 부모 것이 merge되고, `@ConfigurationProperties`는 쓸 수 있다.

범위 밖: 운영 fat jar에서의 재로딩, 자식 전용 서드파티 jar, 원격 재로딩, 자식 auto-configuration.

## 테스트

```bash
./gradlew build
```

**엔진** (`reload`)

- `HotReloadIntegrationTest`: `javax.tools.JavaCompiler`로 컨트롤러 v1/v2/v3를 컴파일해 배포하면서 교체, 부모 bean 공유, 실패 격리, drain, 20회 교체 후 클래스로더 수거, 부모에 MVC 인프라 없음을 확인한다.
- `AopLeakWith*Test`: 부모에 트랜잭션 매니저, `@EnableCaching`, `@Aspect`, 사용자 정의 `Advisor`가 있을 때 자식 AOP가 동작하는지, 20회 교체 후 이전 클래스로더가 모두 수거되는지 확인한다. 실패하면 `LeakDiagnostics`가 부모 쪽에서 자식 클래스를 붙잡은 `Map`을 경로와 함께 보여 준다.
- `*ScenarioTest`: 일반 웹 앱 기능(AOP, `@Async`/`@Scheduled`, 이벤트, 세션·스코프, 캐시, 필터, 보안, actuator 등)이 재로딩에서도 일반 Boot 앱처럼 동작하는지 확인한다. 현재 엔진에서 실패하는 시나리오는 `@KnownIssue`로 표시해 기본 `test`에서 빼고 `./gradlew :reload:knownIssueTest`로 따로 돌린다. 결과는 `docs/reload-aop-proxy-risks.md` 8장.
- `PureJavaParentScenarioTest`: 부모 패키지에 순수 자바 코드만 두는 배치. `ConsumerApp`이 애플리케이션 클래스와 부모·자식 코드를 한 출력 디렉터리에 컴파일하고 부모 클래스로더도 그 디렉터리를 보게 해서 실제 소비자 앱과 같은 배치로 띄운다. 결과는 같은 문서 9장.
- `LayoutScenarioTest`: 부모 `@Component` + 자식 AOP·설정 배치, 권장 배치(부모 인프라 + 자식 컨트롤러·서비스), Advisor 위치별 적용 여부를 `ConsumerApp`으로 비교한다. 결과는 같은 문서 10장.
- `LibraryScanScenarioTest`: 스캔 패키지와 겹치는 라이브러리 클래스(`@Component`, `@AutoConfiguration`, 자식 클래스패스 밖)가 부모에만 등록되고 자식 세대에는 등록되지 않는지, 자식에 Spring Boot auto-configuration이 없는지 확인한다.
- `ConfigurationPlacementCheckerTest`, `ConfigurationPlacementScenarioTest`: 설정 배치 규칙 검사. 허용·위반 판정(단위 테스트)과 실제 소비자 앱 배치에서의 경고, 변경 시에만 다시 경고, 끄기 설정을 확인한다.
- `GenerationCacheCleanerTest`: 공개 clear API가 없어 리플렉션으로 비우는 Spring·Spring Security 내부 캐시 필드가 있는지, 비워지는지 확인한다(Spring을 올려 필드 이름이 바뀌면 여기서 실패).
- `BoundaryCheckerTest`: 필드 타입, 메서드 시그니처, 애플리케이션 클래스에서의 자식 참조를 찾는지, 소유권 규칙이 맞는지 확인한다.
- `ReloadApiIntegrationTest`: `api` 모드에서 파일 변경만으로는 재로딩되지 않고, `POST`로 재로딩되며, 실패하면 500과 원인을 돌려주고 기존 세대가 유지되는지, `GET` 상태 조회와 405 처리를 확인한다. `HotReloadIntegrationTest`는 기본(`watch`) 모드에 API가 없는지도 확인한다.
- `OnReloadApiConditionTest`: `reload.trigger.mode` 값별 API 등록 여부.
- `ReloadAutoConfigurationImportFilterTest`: `reload.enabled`에 따른 MVC auto-configuration 제외.

**소비자** (`sample-apps/greeting`)

- `GreetingReloadTest`: 설정 없이 자동 감지된 배치로 부모에는 부모 소유 bean만 있고, 컨트롤러는 자식 세대(`GenerationClassLoader`)가 부모 bean을 주입받아 서비스하며, `reload()` 후 클래스로더만 바뀌는지 확인한다.
- `GreetingWithoutReloadTest`: `reload.enabled=false`에서 같은 코드가 일반 Spring MVC 앱으로 동작하는지 확인한다.

## Windows에서 사용자 경로에 한글이 있을 때

`GRADLE_USER_HOME`(기본 `C:\Users\<사용자>\.gradle`)에 한글 같은 비 ASCII 문자가 있고 시스템 코드페이지가 949이면,
Gradle이 컴파일/테스트 worker를 띄울 때 쓰는 `@argfile`의 경로를 JDK가 읽지 못해 다음 오류로 실패한다.

```
오류: 기본 클래스 worker.org.gradle.process.internal.worker.GradleWorkerMain을(를) 찾거나 로드할 수 없습니다.
```

ASCII 경로를 Gradle 홈으로 지정하면 된다.

```powershell
$env:GRADLE_USER_HOME = 'D:\gradle-home'   # 또는 시스템 환경 변수로 등록
./gradlew build
```

Gradle wrapper는 9.5.1이다. Gradle 8.14는 JDK 25에서 실행되지 않으므로, JDK 17~24로 Gradle을 띄울 수 있다면 8.14.x로 바꿔도 된다.
Java 17 toolchain이 없으면 foojay 플러그인이 자동으로 내려받는다.
