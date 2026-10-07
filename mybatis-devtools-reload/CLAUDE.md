# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 개요

Spring Boot DevTools와 함께 쓰는 MyBatis mapper XML 런타임 리로드 스타터. Java 변경은 DevTools restart에 맡기고, mapper XML 변경은 재시작 없이 해당 namespace만 교체한다. 사용법·속성 표·제약 사항은 `README.md`에 있다.

- `mybatis-devtools-reload` — 라이브러리(auto-configuration). 패키지 `io.devreload.mybatis`
- `sample-app` — 수동 확인용 예제(H2, `/users`, `/users/count`)

버전은 `gradle.properties`에서 관리한다(Spring Boot 3.5.x, mybatis-spring-boot-starter 3.0.x). Java 21 toolchain(루트 `build.gradle`), Gradle wrapper 9.8.0. toolchain 다운로드 저장소는 설정돼 있지 않으므로 JDK 21이 Gradle에 보여야 한다(데몬 JVM이 21이거나 `org.gradle.java.installations.paths`로 지정).

## 명령

```bash
./gradlew build                                   # 전체 빌드 + 테스트
./gradlew :mybatis-devtools-reload:test           # 라이브러리 테스트
./gradlew :mybatis-devtools-reload:test --tests "io.devreload.mybatis.MapperReloaderTest"
./gradlew :mybatis-devtools-reload:test --tests "io.devreload.mybatis.MapperReloaderTest.reloadIsRepeatable"
./gradlew :sample-app:bootRun                     # 예제 실행 후 mapper/UserMapper.xml 을 고쳐 저장 → curl localhost:8080/users
```

lint/포맷터 설정은 없다. 테스트는 `mybatis-devtools-reload` 모듈에만 있다.

## 아키텍처

### 진입점이 두 개다

| 등록 파일 | 클래스 | 실행 시점 |
|---|---|---|
| `META-INF/spring.factories` | `MapperReloadDevToolsEnvironmentPostProcessor` | 컨텍스트 생성 전, DevTools restart마다 |
| `META-INF/spring/...AutoConfiguration.imports` | `MapperReloadAutoConfiguration` → `MapperReloadService` | `MybatisAutoConfiguration` 이후 |

EnvironmentPostProcessor는 bean이 생기기 전에 돌기 때문에 `MapperReloadProperties`를 쓰지 못하고 `Binder`로 `mybatis-reload.*`를 직접 읽는다. 그래서 **기본값이 두 군데에 있다**: `enabled`/`devtools-integration`의 기본값은 properties 클래스와 post processor의 `orElse(...)` 양쪽에 있고, exclude 패턴만 `DEFAULT_RESTART_EXCLUDE` 상수를 공유한다. 속성을 추가하거나 기본값을 바꾸면 두 클래스와 README 속성 표를 함께 고쳐야 한다.

### 리로드 흐름

```
MapperFileWatcher (데몬 스레드, debounce 후 배치 전달)
  → MapperReloadService.apply()      파일 → 대상 Configuration 결정
    → MapperReloader.reload()        Configuration 하나에서 namespace 교체
      → ConfigurationAccessor        MyBatis 내부 registry 리플렉션
```

- **`MapperReloadService`** (`SmartLifecycle`): `appliedContent`에 마지막으로 적용한 바이트를 보관해 내용이 같으면 건너뛴다(`reload(Path)` 공개 메서드는 강제 적용). `MapperXml.read()`로 namespace만 먼저 읽고(DTD를 받아오지 않음), 그 namespace를 이미 아는 `Configuration`에만 적용한다. 아무도 모르는 namespace는 factory가 하나일 때만 등록한다. 시작 시 `syncWithClasspath`가 감시 루트 기준 상대 경로로 classpath 사본을 찾아 소스와 다르면 소스를 다시 적용한다.
- **`MapperReloader`**: `Configuration`을 monitor로 잡고 ① namespace의 XML 정의 항목 제거 → ② `XMLMapperBuilder.parse()` → ③ 실패 시 스냅샷 복원. public이며 Spring 없이 단독으로 쓸 수 있다.
- **`ConfigurationAccessor`**: 런타임 클래스부터 상위로 필드를 찾으므로 registry를 재선언한 하위 클래스(MyBatis-Plus `MybatisConfiguration`)도 처리된다.

### 깨지기 쉬운 전제 (수정 전에 확인)

- **애노테이션 statement 구분**: MyBatis가 `@Select` 등과 MyBatis-Plus 주입 statement의 resource를 `"... .java (best guess)"`로 기록하는 것에 의존한다. 이 접미사로 XML statement만 골라 지운다.
- **`StrictMap` 우회**: registry는 `HashMap`을 상속한 `StrictMap`이고 `put`/`get`만 오버라이드한다. 제거는 iterator로, 복원은 `putAll`로 해서 중복 키 검사를 피한다. short-name 키(`findAll`)는 값의 identity로 full 키와 함께 제거한다. `put`으로 바꾸면 복원이 깨진다.
- **부분 제거 규칙**: 남아 있는 애노테이션 statement가 참조하는 resultMap/parameterMap은 지우지 않는다. cache는 새 XML이 `<cache>`를 선언할 때만 지운다. `<selectKey>` key generator는 제거된 statement id로 찾는다.
- **`IncompleteTracker`**: 파싱 후 MyBatis의 incomplete 큐에 새 항목이 남으면(없는 `resultMap`, `cache-ref`, include 참조) 예외 없이 끝났더라도 리로드를 거부하고 롤백한다.
- **클래스로더**: 서비스는 컨텍스트 bean이라 DevTools의 restart classloader에 속하고, restart마다 watcher 스레드와 함께 닫히고 새로 만들어진다. 파싱 중에는 TCCL을 애플리케이션 classloader로 바꿔 `resultType`이 restart classloader 기준으로 해석되게 한다. static 캐시나 컨텍스트 밖에서 사는 스레드를 추가하면 이전 classloader가 누수된다.
- **감시 대상은 소스 디렉터리**(`src/main/resources`)이고 상대 경로는 `user.dir` 기준이다. `bootRun`은 작업 디렉터리가 모듈이라 기본값이 맞지만, 다른 작업 디렉터리에서 실행하면 `watch-dirs`를 지정해야 한다.

### 의존성 범위

라이브러리는 Spring Boot autoconfigure와 MyBatis starter를 `compileOnly`로만 의존한다(사용하는 애플리케이션이 제공). 그래서 로깅은 `org.apache.commons.logging`(spring-jcl)을 쓰고, DevTools는 클래스가 아니라 속성 이름(`spring.devtools.restart.additional-exclude`)만 참조한다. 새 런타임 의존성을 추가하지 말 것.

## 테스트 구성

- `MapperReloaderTest`는 Spring 컨텍스트 없이 순수 MyBatis `Configuration` + H2 in-memory로 돈다. mybatis-spring과 같은 순서를 재현하려고 애노테이션 mapper를 먼저 등록한 뒤 XML을 로드한다.
- `MapperFileWatcherTest`는 실제 파일 시스템 이벤트와 `Thread.sleep`에 의존한다.
- `MapperReloadService`와 auto-configuration은 자동 테스트가 없고 `sample-app`으로 수동 확인한다.
- MyBatis 버전을 올릴 때는 리플렉션 대상 필드(`mappedStatements`, `resultMaps`, `parameterMaps`, `keyGenerators`, `sqlFragments`, `caches`, `loadedResources`)가 그대로인지 테스트로 확인한다.

## 관례

- Javadoc·주석과 `README.md`는 한국어로 쓴다(식별자·MyBatis 용어는 원문 유지). 로그·예외 메시지는 영어다. `MapperReloadProperties` 필드의 Javadoc은 configuration metadata의 속성 설명으로 그대로 들어간다.
- "왜 그렇게 하는지"를 클래스 Javadoc에 적는다(예: `StrictMap` 우회 이유). 동작을 바꾸면 해당 Javadoc과 README의 "동작 방식"/"제약"도 맞춘다.
