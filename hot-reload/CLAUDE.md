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
./gradlew :sample-apps:greeting:hotReloadRun
./gradlew :sample-apps:greeting:classes --continuous

# 샘플 실행 (api 모드): 컴파일 후 POST /_reload 한 번 호출
./gradlew :sample-apps:greeting:hotReloadRun -PappArgs='--reload.trigger.mode=api'
./gradlew :sample-apps:greeting:triggerReload --continuous   # -PreloadUrl=... 로 주소 변경
```

- **Windows + 한글 사용자 경로 주의**: `GRADLE_USER_HOME`이 기본값(`C:\Users\<한글>\.gradle`)이고 코드페이지가 949이면 worker 기동이 `GradleWorkerMain을(를) 찾거나 로드할 수 없습니다`로 실패한다. 이 환경이 그렇다. `$env:GRADLE_USER_HOME = 'D:\gradle-home'`처럼 ASCII 경로를 지정하고 실행한다.
- Gradle wrapper 9.5.1, Java 17 toolchain(없으면 foojay가 내려받음). 모든 `JavaCompile`에 `-parameters`가 붙는다.
- 린트/포매터 설정은 없다. 코드는 탭 들여쓰기(Spring 스타일)를 따른다.

## 아키텍처

현재 스펙은 `docs/hybrid-reload-spec.md`다. `reload.business-packages`를 지정한 hybrid 모드에서 소비자 앱은 **부모 context**(내장 Tomcat, 공용 bean, MVC 없음)가 되고, 명시한 업무 코드를 새 `GenerationClassLoader`로 로드한 **자식 context(세대)**가 `DispatcherServlet`을 가진다. 업무 패키지를 지정하지 않으면 일반 Boot MVC를 유지하고 변경 시 전체 JVM 재시작만 지원한다.

부트스트랩 순서 (여러 파일에 걸쳐 있음):
1. `ReloadApplicationListener` (`META-INF/spring.factories`) — 부모 refresh 전에 `ReloadLayout`(자식 클래스패스·소유권)을 결정하고, 부모 컴포넌트 스캔에서 자식 소유 클래스를 빼는 `ReloadParentTypeExcludeFilter`를 등록한다.
2. `ReloadAutoConfigurationImportFilter` — hybrid 모드에서만 부모의 `DispatcherServlet`/`WebMvc`/`ErrorMvc` auto-configuration을 제외한다.
3. `ReloadAutoConfiguration` — `GenerationManager`와 `/`에 매핑된 `ReloadingDispatcherServlet`을 등록한다. `api`/`both` 모드면 `OnReloadApiCondition`으로 `ReloadTriggerServlet`도 등록한다.
4. `GenerationManager.onApplicationReady` — hybrid 모드면 첫 세대를 만들고, watch 모드면 `ChangePoller`를 시작한다.

재로딩: `GenerationManager.reloadWithResult()`(synchronized)가 새 `GenerationApplicationContext`를 만들고, 성공하면 교체 후 이전 세대를 drain → dispose → `GenerationCacheCleaner`로 정리한다. 실패하면 이전 세대가 계속 응답한다. `ReloadingDispatcherServlet`은 요청마다 현재 세대를 acquire/release한다(세대 없음 → 503).

클래스 소유권 규칙 (`layout/ClassOwnership`): 기본은 부모. 명시한 `business-packages`만 자식이며, `parent-packages`와 애플리케이션 진입 클래스는 부모가 우선한다. `GenerationClassLoader`는 업무 클래스에 child-first이며 삭제된 업무 클래스는 부모로 fallback하지 않는다. 부모 빈이 자식 클래스·인스턴스를 보유하면 안 된다(`BoundaryChecker`는 보조 진단이다).

자식에는 Boot auto-configuration이 없다. 필요한 인프라는 `child/`의 클래스들이 매 세대 context에 register한다(`ChildWebMvcConfig`, `ChildInfrastructureConfiguration`). **부모의 `Advisor` bean은 자식에 적용하지 않는다** — 적용하면 부모 쪽 메서드 메타데이터 캐시가 이전 세대 클래스로더를 붙잡아 누수가 생긴다(`AopLeakWith*Test`로 검증). `ChildAspectJAutoProxyCreator`가 이를 차단한다.

설정 배치 규칙: **starter와 인프라 설정은 부모, 검증된 세대 통합은 `GenerationIntegration`**에 둔다. 자식 MVC는 부모 `WebMvcRegistrations`를 사용하되 어댑터는 세대마다 생성한다. ProObject 22는 별도 통합으로 실행 빈과 요청 컨텍스트를 세대에 바인딩한다. 임의의 부모 BPP/Advisor 복제나 모든 starter의 부분 재로딩을 가정하지 않는다.

`ChangePlanner`는 업무 코드만 변경되면 세대 교체, 설정·부모 코드·리소스·JAR·인프라 메타데이터가 변경되면 전체 재시작을 선택한다. 혼합 변경은 전체 재시작이 우선한다. `RestartLauncher`가 없으면 HTTP 409로 재시작 필요 상태를 알리고 기존 세대를 유지한다. Launcher 아래에서는 202 응답 뒤 JVM을 종료하고 새 프로세스를 실행한다. 외부 JEUS 서버는 별도 supervisor 통합이 필요하다.

패키지 의존 방향: `autoconfigure` → `generation` → `watch`/`layout`/`child`.

## 작업 시 주의

- `reload/src/main/java/com/example/reload/devtools/**`는 spring-boot-devtools v3.5.16에서 복사한 코드다(`reload/NOTICE`). 라이선스 헤더와 원본 로직을 유지하고, devtools 의존성은 추가하지 않는다.
- 클래스로더 누수가 이 프로젝트의 핵심 위험이다. 부모 bean/정적 캐시에 자식 클래스·인스턴스·람다를 저장하는 변경은 피하고, 관련 변경 후에는 `HotReloadIntegrationTest`와 `AopLeakWith*Test`(20회 교체 후 이전 클래스로더 수거 확인, 실패 시 `LeakDiagnostics`가 참조 경로 출력)를 돌린다.
- 엔진 통합 테스트는 `ControllerCompiler`(`javax.tools.JavaCompiler`)로 컨트롤러 소스를 런타임에 컴파일해 임시 `reload.classpath`에 배포하는 방식이다.
- `reload` 모듈은 servlet API, Jackson, `spring-tx`를 `compileOnly`로만 가진다. 런타임에는 소비자 앱이 제공한다고 가정한다.
- `docs/hot-reload-project-spec.md`는 초기 생성 지시서다. 현재 동작은 `docs/hybrid-reload-spec.md`, README와 코드를 기준으로 판단한다. 분류 정책 테스트는 공개 API를 사용하고, 과거 lifecycle 테스트의 설정 배포는 `GenerationFixtures`로 격리한다.
