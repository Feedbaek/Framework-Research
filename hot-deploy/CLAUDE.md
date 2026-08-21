# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

Gradle 9.5.1 wrapper, Java 21 toolchain, Spring Boot 4.1.0 + Spring Cloud 2025.1.2. Windows에서는 `gradlew.bat`, POSIX shell에서는 `./gradlew`.

```bash
./gradlew clean test              # 전체 모듈 테스트
./gradlew :runtime:test           # 단일 모듈
./gradlew :runtime:test --tests 'my.spring.research.runtime.HotDeployIntegrationTest'
./gradlew :runtime:test --tests '*CandidateContextFactoryTest.rejectsArtifactsOutsideRepositoryRoot'
./gradlew :app:jar                # 배포용 plain JAR (bootJar 아님)
```

`:runtime:test`는 `:app:jar`에 의존하며 빌드된 sample app JAR의 절대 경로를 `sampleAppJar` system property로 넘긴다. `runtime` 테스트 중 artifact를 로딩하는 것들(`HotDeployIntegrationTest`, `CandidateContextFactoryTest`, `ArtifactValidatorTest`, `AppModuleLoaderTest`, `*ProbeTest`)은 이 property가 없으면 실패하므로 IDE에서 단독 실행할 때도 반드시 설정해야 한다.

`*ProbeTest` 3종은 docs 17.6의 leak/endurance 검증 중 실행 가능한 부분을 담고 있다. 아래 [Unload invariants](#unload-invariants-검증됨)와 [Extending the app programming model](#extending-the-app-programming-model)의 근거이므로, 관련 동작을 바꿀 때 함께 확인한다.

## Run

`RUNTIME_ADMIN_PASSWORD`는 필수 환경변수다(`application.yaml`에 default 없음). `bootRun`의 workingDir은 root project이므로 `repository-root`인 `deployments/repository`는 root 기준 상대 경로다. Business API는 `8080`, management API는 loopback `127.0.0.1:8081`.

실행/배포/rollback/refresh curl 예시는 [`README.md`](README.md)에 있다.

## Architecture

설계 근거·상태 전환·검증 계획 전문은 [`docs/hot-deploy-architecture.md`](docs/hot-deploy-architecture.md)에 있다. 코드를 바꾸기 전에 해당 문서의 불변식을 확인한다.

hot deploy 성립 메커니즘 요약과 실무 적용 판단(인프라 고정 / 서비스 로직만 교체, `RuntimePort` 확장, 서드파티 라이브러리 배치, Spring annotation 개방 여부, 투입 전 미구현 격차)은 [`docs/hot-deploy-adoption-guide.md`](docs/hot-deploy-adoption-guide.md)에 있다.

### 3-module boundary

| module | 역할 | 제약 |
|---|---|---|
| `runtime-api` | Spring-free 공개 계약 (`AppModule`, `BusinessHandler`, `BusinessRequest/Response`, `AppMetadata`, `RuntimeMessageProvider`, `RuntimePort`) | framework 의존성 금지. `java-library`만 적용 |
| `app` | 교체 대상 비즈니스 JAR | `runtime-api`를 `compileOnly`로만 사용(JAR에 중복 포함 금지). Spring import/annotation 금지. plain `jar` |
| `runtime` | Spring Boot 호스트 (한 번만 기동) | app JAR를 classpath에 넣지 않는다. 기동 후 외부 repository에서 별도 classloader로만 로딩 |

이 경계를 깨는 변경(예: `app`에 Spring 의존성 추가, `runtime-api`에 Spring type 노출)은 프로젝트의 존재 이유를 무효화한다.

### Where new code goes

**`runtime-api`에 무언가를 올리는 것은 그것을 hot deploy 불가능한 영역으로 옮기는 것이다.** 계약 변경은 runtime 재컴파일·재기동을 요구하고(docs 3.2) `AppMetadata.runtimeApiRange` 호환성 검증 대상이 된다. `runtime-api`가 커질수록 무중단 교체 가능 범위가 줄어든다.

배치 기준은 **호출 방향**이다.

| 방향 | 위치 | 예 |
|---|---|---|
| app이 구현 → runtime이 호출 | `runtime-api` interface | `AppModule`, `BusinessHandler`. **진입점만, 소수로 유지** |
| runtime이 구현 → app이 호출 | `runtime-api` interface (`RuntimePort`) | `RuntimeMessageProvider`. **늘어나야 할 쪽** |
| app이 구현 → app이 호출 | **app 내부** | service, repository, 내부 POJO. `runtime-api`에 올리지 않는다 |

| 만들려는 것 | 위치 |
|---|---|
| 경계를 넘는 DTO | `runtime-api`의 **record** — interface로 만들지 않는다 |
| app 내부 DTO | app 내부 record/class (경계를 넘지 않는 한 자유) |
| service / repository / POJO | **app 내부 plain class**, `components()`에 등록 |
| controller | `BusinessHandler` 구현 + (확장 시) route 메타데이터 |
| runtime이 제공하는 기능 | `runtime-api` port interface |

**DTO를 interface로 만들면 안 되는 이유:** app 구현체가 응답으로 나가면 parent `ObjectMapper`의 serializer 캐시가 `Class`를 강한 참조로 잡아 classloader가 영구히 남는다(docs 6장). `BusinessRequest`/`BusinessResponse`가 `record` + `Map<String,String>`인 것이 이 때문이다. 도메인 타입이 필요하면 **app 내부에서만 쓰고 경계에서 `BusinessResponse`로 평탄화**한다.

service에 interface가 필요하면(테스트 더블 등) **app 안에** 두면 된다. runtime은 진입점만 알면 되고, 나머지 협력 객체는 `components()` 등록만으로 생성자 주입이 동작한다.

### Request path

`RuntimeDispatchController` (`POST /api/business`, 고정 mapping — hot deploy 대상 아님)
→ `DeploymentRouter.route()` → read lock 아래에서 active `DeploymentSlot` 조회 후 lease 획득
→ `DeploymentLease` 생성 시 TCCL을 app classloader로 교체, `close()`에서 복원 + lease 반환
→ app의 `BusinessHandler.handle()`.

read lock은 handler 실행 중 유지하지 않는다. slot 생존을 보장하는 것은 lease count다.

### Deploy path

`HotDeployEndpoint` (`POST /actuator/hotdeploy`, action=`deploy`|`rollback`)
→ `DeploymentManager` → `CandidateContextFactory.load()`:
`ArtifactValidator` → `AppClassLoaderFactory` → `AppModuleLoader`(ServiceLoader, provider 정확히 1개) → child `GenericApplicationContext` 생성 → component를 `RootBeanDefinition`(constructor autowire)으로 등록 → **`refresh()` 정확히 1회** → entry point 조회.
→ 성공한 candidate만 `DeploymentRouter.activateSlot()`으로 write lock 아래 cutover, 이전 slot은 `DRAINING`.

`CandidateContextFactory`는 실패 시 context와 classloader를 모두 닫는다. cutover 전에 실패하면 active slot은 전혀 바뀌지 않는다.

### Locking / concurrency

- `DeploymentControlPlane`: 전역 `ReentrantLock`. deploy / rollback / status / refresh가 공유한다. `tryAcquire()` 실패 시 `DeploymentBusyException` → HTTP 409.
- `DeploymentRouter`: `ReentrantReadWriteLock`. 요청의 slot 선택 + lease 획득(read)과 cutover(write)가 같은 lock에 참여하므로 cutover 이후 성공하는 lease는 new slot만 본다.
- lock 순서는 항상 control plane → router write lock. 역순 취득 코드를 추가하지 않는다.
- **active lease가 남아 있는 slot의 context/classloader는 절대 닫지 않는다.** drain timeout은 강제 종료 시각이 아니라 경고 신호다. `DeploymentSlot.close()`를 직접 호출하면 lease가 남았을 때 `UNLOAD_PENDING`으로 전환만 하고, 마지막 lease 반환 시 `releaseLease()`가 다시 닫는다. 단 **재배포 경로는 `close()`를 부르지 않고 `requestClose()`만 하므로 상태가 `DRAINING`으로 남는다** — 아래 [Unload invariants](#unload-invariants-검증됨) 참고.

### State machine

`DeploymentState`: `READY → ACTIVE → DRAINING → (RETAINED_FOR_ROLLBACK) → UNLOAD_PENDING → UNLOADING → UNLOADED`, 실패 시 `FAILED`, drain 초과 시 `DRAIN_TIMEOUT`.

`rollback-retention-count > 0`이면 이전 slot을 TTL 동안 `RETAINED_FOR_ROLLBACK`으로 살려 두고 rollback 시 재활성화한다. retention을 넘긴 deploymentId는 `artifactHistory`(bounded, `max-artifact-history`)에 남은 경로로 새 classloader/context에 다시 로드한다. `DeploymentManager.scheduledCleanup()`이 `runtime.hot-deploy.cleanup-interval`(기본 5s)마다 만료 retention과 drain timeout을 처리한다.

`deploymentId`는 artifact의 SHA-256이다. rollback 요청은 이 값을 사용한다.

### Classloader isolation

`AppClassLoader`(parent-first `URLClassLoader`)는 `my.spring.research.runtime.api.`만 허용하고 `my.spring.research.runtime.`(runtime 구현), `org.springframework.`, `jakarta.servlet.`, `javax.servlet.`은 `ClassNotFoundException`으로 차단한다. `ArtifactValidator`가 같은 규칙을 JAR entry 레벨에서도 강제한다(+ path traversal, entry 수, 압축 해제 크기, `allowed-class-prefixes` 밖의 class 거절, service descriptor 존재 확인).

leak 방지를 위해 runtime의 parent singleton은 app `Class`/bean/lambda/exception 객체를 slot 밖에 저장해서는 안 된다. `DeploymentStatus`처럼 parent-loaded value와 문자열만 밖으로 나간다.

### Unload invariants (검증됨)

`ClassLoaderUnloadProbeTest` / `RedeployUnloadProbeTest`가 실측한 동작이다.

- `LoadedCandidate.close()` 이후 app classloader와 app class가 GC 가능해진다. **단 이것은 GC eligibility지 class unloading 보장이 아니다**(docs 3.1-6).
- **기본 설정(`rollback-retention-count: 1`)에서 재배포 즉시 unload는 일어나지 않는다.** 이전 slot이 `RETAINED_FOR_ROLLBACK`으로 TTL(기본 5분) 동안 강하게 참조되고, `scheduledCleanup()`(5초 주기)이 만료를 처리한다. 즉시 회수가 필요하면 retention을 `0`으로 둔다.
- in-flight 요청이 있는 slot은 **`DRAINING` 상태를 유지**하며 닫히지 않는다(`DrainManager.drainOrRetain()`이 `requestClose()`만 호출). 마지막 lease 반환 시 `releaseLease()`가 `close()`를 재시도해 `UNLOADED`가 된다. `UNLOAD_PENDING`은 retention 만료 경로에서 쓰이므로, **"아직 안 닫힌 slot"을 판별할 때 두 상태를 모두 봐야 한다.**
- `AutoCloseable`을 구현한 app 빈은 `context.close()` 시 `close()`가 destroy method로 추론되어 호출된다. `@PreDestroy`는 `CommonAnnotationBeanPostProcessor`가 등록되지 않아 **무시된다.**

slot이 회수되려면 `DeploymentRouter.active`, `DeploymentManager.retained`, `DeploymentManager.unloading` 세 참조가 모두 풀려야 한다. `context.close()`는 singleton 맵만 비우고 **bean definition은 남기므로**(`RootBeanDefinition.beanClass` → app `Class` → classloader), slot 객체 자체가 버려져야 체인이 끊긴다.

**아직 구현되지 않은 정리 단계(docs 14장 5~8번):** `LoadedCandidate.close()`는 `context.close()` + `classLoader.close()`가 전부다. app이 등록한 thread/executor, `ThreadLocal`, JDBC driver, MBean, shutdown hook, Spring introspection 캐시(`CachedIntrospectionResults.clearClassLoader`)는 정리하지 않는다. 현재 sample app이 이런 리소스를 잡지 않아 프로브가 통과하는 것이므로, **app에 실제 리소스를 쓰는 계층을 추가하면 프로브는 초록불인 채로 실제로는 샌다.** app 리소스는 `AutoCloseable`로 통일한다.

### Config refresh (코드 배포와 분리)

raw `/actuator/refresh`는 노출하지 않는다. `POST /actuator/runtimeRefresh`(`RuntimeRefreshController`)만 사용하며, 이 controller는 component scan 대상이 아니라 `runtime/src/main/resources/META-INF/spring/...ManagementContextConfiguration.imports`를 통해 **management child context에만** 등록된다(`@SpringBootApplication`의 scan base는 `my.spring.research.runtime`이고 controller는 `my.spring.research.management` 패키지에 있다). management endpoint를 추가할 때 이 구조를 유지한다.

`RuntimeRefreshCoordinator`는 candidate map 전체를 먼저 검증하고, allowlist 밖 key가 하나라도 있으면 `Environment`를 건드리지 않고 전체를 거절한다. 검증된 값만 `runtimeRefreshOverrides` `MapPropertySource`로 원자적으로 교체한 뒤 `EnvironmentChangeEvent` + `RefreshScope.refresh("runtimeMessageProvider")`를 실행하고, 실패 시 이전 property source로 되돌린다.

**refreshable key를 추가할 때는 세 곳을 함께 고쳐야 한다:** `application.yaml`의 `runtime.hot-deploy.refresh-allowlist`, `RuntimeRefreshCoordinator.SUPPORTED_KEYS`, 그리고 `validateValue()`의 switch. 어느 하나라도 빠지면 key가 거절된다.

refresh는 runtime bean만 갱신한다. app classloader/child context를 refresh하거나 교체하지 않는다(`HotDeployIntegrationTest`가 classloader identity 유지를 검증한다).

### Security

`SecurityConfig`의 filter chain 순서: (1) `/actuator/runtimeRefresh` → `ROLE_RUNTIME_ADMIN`, (2) 그 외 actuator endpoint → `health`/`info`만 permitAll, 나머지 `ROLE_RUNTIME_ADMIN`, (3) `/api/business`와 `/error`만 permitAll, 나머지 `denyAll`. 새 public endpoint는 (3)에 명시적으로 추가해야 하며 그렇지 않으면 차단된다.

hot-deployed JAR는 sandbox가 아니다. classloader와 검증은 사고 방지용이며 신뢰된 내부 artifact만 대상으로 한다.

## Extending the app programming model

app에서 `@Transactional` 등 Spring annotation을 쓰자는 요구가 나오면, 아래를 먼저 확인한다. `RuntimeAdvisorOnAppBeanProbeTest`가 근거다.

**현재 안 되는 이유는 두 겹이다.** (1) `AppClassLoader`가 `org.springframework.`를 차단한다. (2) 더 근본적으로 `CandidateContextFactory`가 `AnnotationConfigUtils.registerAnnotationConfigProcessors()`를 호출하지 않아 **BeanPostProcessor가 하나도 없다.** (1)만 풀면 annotation은 로드되지만 아무 일도 일어나지 않고 조용히 무시된다.

**AOP 인프라의 배치 규칙:**

| 요소 | 위치 | 근거 |
|---|---|---|
| auto-proxy creator (BPP) | **child 필수** | BPP는 parent→child로 상속되지 않는다. parent에만 있으면 app 빈은 프록시조차 안 된다 |
| Advisor / Interceptor / Aspect | **parent 가능** | `BeanFactoryAdvisorRetrievalHelper`가 `beanNamesForTypeIncludingAncestors()`로 parent까지 탐색한다 |
| annotation | `runtime-api` | parent-loaded라 class identity 문제가 없다 |

즉 aspect 자산은 runtime root에 한 번만 정의하고, 배포마다 child에 새로 등록하는 것은 auto-proxy creator뿐이다.

**켜기 전에 반드시 고쳐야 할 두 가지:**

1. `AppClassLoader`의 공유 패키지에 `org.springframework.aop.`, `org.aopalliance.`(CGLIB 사용 시 `org.springframework.cglib.`)를 추가. app 소스가 Spring 무의존이어도 **생성된 프록시가 `SpringProxy`·`Advised`를 구현**하므로 보이지 않으면 `... is not visible from class loader`로 실패한다. 컴파일 의존성과 런타임 로딩 가시성은 별개다.
2. `CandidateContextFactory`의 `context.getBean(validatedModule.entryPoint())`(구체 클래스 조회)를 `getBean(BusinessHandler.class)`로 바꾸거나 `proxyTargetClass=true`를 강제. JDK 프록시는 구체 타입을 상속하지 않아 **AOP를 켜는 순간 이 조회가 `NoSuchBeanDefinitionException`으로 깨진다.**

**기능별 위험도:** `@Transactional`·`@Cacheable`·커스텀 `@Aspect`는 프록시와 advisor가 child에 갇히면 관리 가능하다. `@Scheduled`·`@EventListener`는 등록 위치를 틀리면 parent 레지스트리가 app 객체를 영구히 잡는다. **`@Async`는 현재 drain 설계와 정면 충돌한다** — lease는 HTTP 요청만 추적하므로 async task가 lease 밖에서 실행되어 닫힌 context에 접근하거나 classloader를 붙잡는다. Spring Data JPA는 `EntityManagerFactory`가 entity/metamodel/proxy를 붙잡아 부적합하다.

**권장 방향:** annotation을 `runtime-api`에 자체 정의하고 runtime이 대응 interceptor를 child에 등록한다. app은 Spring 무의존을 유지하면서 선언적 사용감을 얻고, runtime이 어떤 횡단 관심사를 열지 통제할 수 있다. 상태(메서드 캐시)를 가진 advisor는 parent에 두면 app `Method`를 붙잡으므로 child에 등록한다.

## Conventions

- 들여쓰기는 tab. Java import는 static → JDK/third-party → `my.spring.research.*` 순으로 그룹핑되어 있다.
- 새 app package를 추가하면 `runtime.hot-deploy.allowed-class-prefixes`(슬래시 구분, 예: `my/spring/research/app/`)에 등록해야 배포가 통과한다.
- `app`의 `AppModule` 구현은 `META-INF/services/my.spring.research.runtime.api.AppModule`에 정확히 하나만 등록한다. component는 public concrete class + public 생성자 1개여야 하고 `entryPoint`는 `components()`에 포함되어야 한다.
- `AppMetadata.runtimeApiRange`는 `RuntimeApiCompatibility`가 해석한다(`[0.0.1,1.0.0)` 범위, `0.0.x` 접두, 정확 일치 지원). 대상 값은 `runtime.hot-deploy.runtime-api-version`.
- runtime 예외는 `RuntimeExceptionHandler`가 `{code, message}` JSON으로 매핑한다. 새 deploy 계열 예외를 만들면 여기와 `HotDeployEndpoint.change()`의 catch 블록을 함께 갱신한다.
