# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Spring Boot 3.5.16 기반 hot-reload 엔진(`reload`)과 그 소비자 샘플(`sample-apps/greeting`)로 이루어진 Gradle 멀티 모듈 프로젝트다. 상세한 동작·설정·제약은 `README.md`가 기준 문서다. 문서와 사용자 응답은 한국어로 쓴다.

## 명령

```bash
./gradlew build                                   # 전체 빌드 + 테스트
./gradlew :reload:test                            # 엔진 테스트만
./gradlew :reload:test --tests 'com.example.reload.generation.HotReloadIntegrationTest'
./gradlew :sample-apps:greeting:test --tests '*GreetingReloadTest'
./gradlew :reload:knownIssueTest                  # @KnownIssue 시나리오(현재 엔진에서 실패하는 것이 정상)
./gradlew :reload:publishToMavenLocal

# 샘플 실행 (watch 모드): 터미널 두 개
./gradlew :sample-apps:greeting:bootRun
./gradlew :sample-apps:greeting:classes --continuous

# 샘플 실행 (api 모드): 컴파일 후 POST /_reload 한 번 호출
./gradlew :sample-apps:greeting:bootRun --args='--reload.trigger.mode=api'
./gradlew :sample-apps:greeting:triggerReload --continuous   # -PreloadUrl=... 로 주소 변경
```

- **Windows + 한글 사용자 경로 주의**: `GRADLE_USER_HOME`이 기본값(`C:\Users\<한글>\.gradle`)이고 코드페이지가 949이면 worker 기동이 `GradleWorkerMain을(를) 찾거나 로드할 수 없습니다`로 실패한다. 이 환경이 그렇다. `$env:GRADLE_USER_HOME = 'D:\gradle-home'`처럼 ASCII 경로를 지정하고 실행한다.
- Gradle wrapper 9.5.1, Java 17 toolchain(없으면 foojay가 내려받음). 모든 `JavaCompile`에 `-parameters`가 붙는다.
- 린트/포매터 설정은 없다. 코드는 탭 들여쓰기(Spring 스타일)를 따른다.

## 아키텍처

핵심 아이디어: 소비자 앱 자체가 **부모 context**(내장 Tomcat, 공용 bean, MVC 없음)가 되고, 앱 출력 디렉터리를 세대마다 새 `GenerationClassLoader`로 로드한 **자식 context(세대)**가 `DispatcherServlet`을 가진다. 부모와 자식은 같은 `build/classes/java/main`을 본다.

부트스트랩 순서 (여러 파일에 걸쳐 있음):
1. `ReloadApplicationListener` (`META-INF/spring.factories`) — 부모 refresh 전에 `ReloadLayout`(자식 클래스패스·소유권)을 결정하고, 부모 컴포넌트 스캔에서 자식 소유 클래스를 빼는 `ReloadParentTypeExcludeFilter`를 등록한다.
2. `ReloadAutoConfigurationImportFilter` — 부모에서 `DispatcherServlet`/`WebMvc`/`ErrorMvc` auto-configuration을 제외한다.
3. `ReloadAutoConfiguration` — `GenerationManager`와 `/`에 매핑된 `ReloadingDispatcherServlet`을 등록한다. `api`/`both` 모드면 `OnReloadApiCondition`으로 `ReloadTriggerServlet`도 등록한다.
4. `GenerationManager.onApplicationReady` — 첫 세대를 만들고 (watch 모드면) `ClassPathChangeWatcher`를 시작한다.

재로딩: `GenerationManager.reloadWithResult()`(synchronized)가 새 `GenerationApplicationContext`를 만들고, 성공하면 교체 후 이전 세대를 drain → dispose → `GenerationCacheCleaner`로 정리한다. 실패하면 이전 세대가 계속 응답한다. `ReloadingDispatcherServlet`은 요청마다 현재 세대를 acquire/release한다(세대 없음 → 503).

클래스 소유권 규칙 (`layout/ClassOwnership`): 기본은 자식. 부모 소유는 `reload.parent-packages`, `@SpringBootApplication` 클래스와 그 중첩 클래스뿐이다. 엔진은 jar로 부모 클래스패스에만 있어 자식 URL에 없으므로 따로 다루지 않는다. `GenerationClassLoader`는 child-first이되 부모 소유 클래스만 부모에 위임한다. 부모 소유 클래스가 자식 소유 클래스를 참조하면 안 된다(`BoundaryChecker`가 시작 시 경고).

자식에는 Boot auto-configuration이 없다. 필요한 인프라는 `child/`의 클래스들이 매 세대 context에 register한다(`ChildWebMvcConfig`, `ChildInfrastructureConfiguration`). **부모의 `Advisor` bean은 자식에 적용하지 않는다** — 적용하면 부모 쪽 메서드 메타데이터 캐시가 이전 세대 클래스로더를 붙잡아 누수가 생긴다(`AopLeakWith*Test`로 검증). `ChildAspectJAutoProxyCreator`가 이를 차단한다.

설정 배치 규칙: **인프라 설정은 부모에, Advisor·BeanPostProcessor로 동작하는 `@Enable*`만 자식에 둔다.** 자식 인프라 설정은 부모 auto-configuration·서블릿 컨테이너가 보지 못하고, 부모의 Advisor·BPP는 자식 bean을 처리하지 않기 때문이다. `layout/ConfigurationPlacementChecker`가 검사하고 `GenerationManager`가 경고한다(부모 쪽은 첫 세대, 자식 쪽은 위반 목록이 바뀐 세대마다).

패키지 의존 방향: `autoconfigure` → `generation` → `watch`/`layout`/`child`.

## 작업 시 주의

- `reload/src/main/java/com/example/reload/devtools/**`는 spring-boot-devtools v3.5.16에서 복사한 코드다(`reload/NOTICE`). 라이선스 헤더와 원본 로직을 유지하고, devtools 의존성은 추가하지 않는다.
- 클래스로더 누수가 이 프로젝트의 핵심 위험이다. 부모 bean/정적 캐시에 자식 클래스·인스턴스·람다를 저장하는 변경은 피하고, 관련 변경 후에는 `HotReloadIntegrationTest`와 `AopLeakWith*Test`(20회 교체 후 이전 클래스로더 수거 확인, 실패 시 `LeakDiagnostics`가 참조 경로 출력)를 돌린다.
- 엔진 통합 테스트는 `ControllerCompiler`(`javax.tools.JavaCompiler`)로 컨트롤러 소스를 런타임에 컴파일해 임시 `reload.classpath`에 배포하는 방식이다.
- `reload` 모듈은 servlet API, Jackson, `spring-tx`를 `compileOnly`로만 가진다. 런타임에는 소비자 앱이 제공한다고 가정한다.
- `docs/hot-reload-project-spec.md`는 초기 생성 지시서로, 모듈 구조(`api`/`host`/`sample-app`)가 현재 코드(`reload` + `sample-apps/greeting`)와 다르다. 현재 동작은 README와 코드를 기준으로 판단한다.
