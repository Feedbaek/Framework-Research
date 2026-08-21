# Hot Deploy 실무 적용 가이드

이 문서는 두 가지를 다룬다.

1. 이 프로젝트에서 **hot deploy가 성립하는 메커니즘** — 무엇이 교체 단위이고, 왜 그 단위가 안전하게 교체되는가.
2. 그 메커니즘 위에서 **Spring 기능과 서드파티 라이브러리를 쓰면서 실무 서비스를 개발하는 방법** — 인프라(DB 커넥션풀 등)는 고정하고 변경이 잦은 서비스 로직만 교체 대상으로 유지하는 구조.

설계 근거·상태 전환·검증 계획 전문은 [`hot-deploy-architecture.md`](hot-deploy-architecture.md)에 있다. 이 문서는 그 위에 **적용 판단과 미구현 격차**를 얹는다.

---

## 1. Hot deploy가 성립하는 메커니즘

### 1.1 교체 단위는 클래스가 아니다

JVM에서 이미 로딩된 클래스는 교체되지 않는다. 유일한 우회로는 **다른 classloader로 같은 이름의 클래스를 새로 로딩**하는 것이고, JVM은 두 클래스를 완전히 별개 타입으로 취급한다.

따라서 이 프로젝트의 교체 단위는 클래스가 아니라 다음 3개가 묶인 덩어리다.

```
DeploymentSlot
 ├─ AppClassLoader             (배포마다 새로 생성, 이름 = "app-<sha256>")
 ├─ GenericApplicationContext  (배포마다 새로 생성, parent = runtime root)
 └─ BusinessHandler            (entry point 인스턴스)
```

`runtime/build.gradle`에 `app` 모듈 의존성이 **없다**는 점이 전제다. app JAR는 기동 시 classpath에 존재하지 않고 `deployments/repository`에서 런타임에 URL로만 열린다. classpath에 있었다면 system classloader가 먼저 잡아 영구히 교체 불가가 된다.

### 1.2 Classloader 격리: parent-first + 차단 목록

`AppClassLoader`(`loader/AppClassLoader.java:9-14`)가 격리의 핵심이다.

| 패키지 | 처리 | 이유 |
|---|---|---|
| `my.spring.research.runtime.api.` | **공유** (parent 로딩) | 계약 타입은 identity가 하나여야 한다 |
| `my.spring.research.runtime.` (그 외) | `ClassNotFoundException` | runtime 구현 은닉 |
| `org.springframework.`, `jakarta.servlet.`, `javax.servlet.` | `ClassNotFoundException` | app의 Spring 오염 차단 |
| 그 외 (`java.*`, 서드파티) | parent-first 위임 | 공용 라이브러리 재사용 |

`BusinessHandler`가 parent-loaded여야 하는 이유가 여기 있다. app이 자기 loader로 로딩했다면 runtime의 `BusinessHandler`와 다른 타입이 되어 `ClassCastException`이 난다. 반대로 app 내부 클래스는 전부 app loader 소유라 slot과 함께 버려진다.

`ArtifactValidator`가 같은 규칙을 JAR 진입 전에 한 번 더 강제한다 — 금지 package class 거절(`:107-118`), `allowed-class-prefixes` 밖 class 거절(`:120-127`), path traversal, 압축 폭탄, `META-INF/services` descriptor 존재 확인.

### 1.3 배포마다 새로 만드는 child ApplicationContext

`CandidateContextFactory.load()`의 순서는 다음과 같다.

```
ArtifactValidator.validate()            → SHA-256 = deploymentId
AppClassLoaderFactory.create()          → 새 classloader
AppModuleLoader.load()                  → ServiceLoader, provider 정확히 1개
validateModule()                        → entryPoint/component의 loader identity 검증
GenericApplicationContext
  ├─ setParent(runtime root)            → runtime bean을 부모에서 주입
  ├─ setClassLoader(appClassLoader)
  ├─ registerBeanDefinition(RootBeanDefinition, AUTOWIRE_CONSTRUCTOR)   (:138-141)
  └─ refresh()                          정확히 1회                       (:57)
getBean(entryPoint)                     → BusinessHandler                (:58)
```

`setParent()` 덕분에 app component가 runtime bean을 **생성자 주입**으로 받는다. 반대 방향(parent가 child를 봄)은 불가능하고, 이 단방향성이 leak 방지의 구조적 근거다.

**실패 시 원자성**이 중요하다. 검증이나 `refresh()`가 실패하면 `catch` 블록이 context와 classloader를 모두 닫고 예외를 던진다(`:70-85`). cutover 이전이므로 active slot은 전혀 바뀌지 않는다. "배포 실패 = 무변화"가 여기서 보장된다.

### 1.4 Lease 기반 원자적 cutover

가장 어려운 문제는 "지금 실행 중인 요청"이다. 답은 `DeploymentLease`다.

```java
// DeploymentRouter:21-24
public BusinessResponse route(BusinessRequest request) {
	try (DeploymentLease lease = acquireLease()) {   // read lock 아래 slot 선택 + 카운트 증가
		return lease.handler().handle(request);      // lock 밖에서 실행
	}                                                // close() → TCCL 복원 + 카운트 감소
}
```

- **read lock은 slot 선택 순간에만 잡는다.** handler 실행 중에는 풀려 있어 배포가 요청을 블로킹하지 않는다.
- cutover(`activateSlot()`)는 **write lock**을 잡는다. 같은 lock에 참여하므로 cutover 이후 성공하는 lease는 반드시 new slot만 본다. 중간 상태가 없다 — 이것이 "원자적"의 의미다.
- 옛 slot의 생존을 보장하는 것은 lock이 아니라 **lease 카운트**다. `DeploymentSlot.close()`는 lease가 남아 있으면 상태만 바꾸고 반환하며(`:176-195`), 마지막 lease 반환 시 `releaseLease()`가 다시 `close()`한다.
- `DeploymentLease` 생성자가 TCCL을 app classloader로 바꾸고 `close()`에서 복원한다. app이 `ServiceLoader`나 TCCL 기반 `Class.forName`을 쓰는 라이브러리를 호출할 때 올바른 loader를 보게 된다.

전체 경로:

```
POST /actuator/hotdeploy {action:deploy, path:...}
  → DeploymentControlPlane (전역 ReentrantLock, tryAcquire 실패 시 409)
  → CandidateContextFactory.load()      ← 실패하면 여기서 종료, 무변화
  → DeploymentRouter.activateSlot()     ← write lock, 이 순간이 cutover
  → 옛 slot: DRAINING → (RETAINED_FOR_ROLLBACK, TTL) → UNLOADED
```

### 1.5 Unload는 보장이 아니다

`ClassLoaderUnloadProbeTest`가 실측한 것은 **GC eligibility**이지 class unloading이 아니다(docs 3.1-6). 실제 회수되려면 `DeploymentRouter.active`, `DeploymentManager.retained`, `DeploymentManager.unloading` 세 참조가 모두 풀려야 한다.

`context.close()`는 singleton 맵만 비우고 **bean definition은 남긴다**(`RootBeanDefinition.beanClass` → app `Class` → classloader). 따라서 slot 객체 자체가 버려져야 참조 체인이 끊긴다.

그리고 `LoadedCandidate.close()`는 현재 `context.close()` + `classLoader.close()`가 전부다. 이것이 4장의 출발점이다.

---

## 2. 실무 적용 모델

### 2.1 변경 빈도로 계층을 자른다

| 계층 | 소유 | 변경 방법 | 근거 |
|---|---|---|---|
| DataSource / HikariCP, JPA `EntityManagerFactory`, MyBatis `SqlSessionFactory` | **runtime (고정)** | 재기동 | 커넥션·네이티브 스레드·metamodel·프록시 캐시 보유. slot 수명과 맞출 수 없다 |
| `PlatformTransactionManager`, Redis/Kafka 클라이언트, HTTP 클라이언트 | **runtime (고정)** | 재기동 | 커넥션 풀·스레드 보유 |
| Security, Actuator, 로깅, Micrometer | **runtime (고정)** | 재기동 | 요청 진입점 자체 |
| **서비스 로직, 도메인 규칙, 검증, DTO 매핑, 쿼리 문자열** | **app JAR (hot)** | hot deploy | 순수 계산 + port 호출뿐. 리소스 미보유 |

판정 규칙은 하나다. **스레드·소켓·네이티브 핸들을 잡는 것은 runtime, 잡지 않는 것만 app.**

DB 스키마 마이그레이션(Flyway/Liquibase)도 runtime 기동 시점이므로 app 배포와 분리된다. app 배포가 스키마 변경을 요구하면 **expand/contract 패턴이 강제**된다: 컬럼 추가 → 재기동 → 새 로직 hot deploy → 이후 옛 컬럼 제거.

### 2.2 인프라 접근은 `RuntimePort` 확장으로

CLAUDE.md의 배치 규칙 중 "runtime이 구현 → app이 호출"이 늘어나야 할 쪽이며, 실무 전환의 대부분이 여기서 일어난다. 현재 `RuntimeMessageProvider` 하나뿐인 자리를 인프라 port로 채운다.

#### 트랜잭션 — `@Transactional` 없이

```java
// runtime-api
public interface TransactionPort extends RuntimePort {
	<T> T required(TxUnit<T> unit);
	<T> T requiresNew(TxUnit<T> unit);

	@FunctionalInterface
	interface TxUnit<T> { T run(); }
}
```

```java
// runtime — RuntimeConfig
@Bean
TransactionPort transactionPort(PlatformTransactionManager tm) {
	TransactionTemplate required = new TransactionTemplate(tm);
	TransactionTemplate requiresNew = new TransactionTemplate(tm);
	requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	return new TransactionTemplateAdapter(required, requiresNew);
}
```

```java
// app — Spring import 0개
public final class OrderService {
	private final TransactionPort tx;
	private final SqlPort sql;

	public OrderService(TransactionPort tx, SqlPort sql) {
		this.tx = tx;
		this.sql = sql;
	}

	public String place(String itemId, int qty) {
		return tx.required(() -> {
			sql.update("UPDATE stock SET qty = qty - :q WHERE id = :id", Map.of("q", qty, "id", itemId));
			return sql.queryOne("SELECT ...", Map.of("id", itemId))
					.map(row -> String.valueOf(row.get("order_id")))
					.orElseThrow();
		});
	}
}
```

콜백 스타일이라 **프록시가 필요 없다.** app이 넘기는 람다는 app-loaded class지만 `TransactionTemplate`이 캐시하지 않고 호출 즉시 버리므로 leak이 없다.

> **주의.** app이 던진 예외가 parent 예외의 `cause`로 감싸져 장기 보관되면 app classloader가 붙잡힌다. port 구현 경계에서 app 예외를 잡아 메시지·스택트레이스를 **문자열로 변환한 뒤 parent-loaded 예외로 다시 던진다**(docs 14장 규칙).

#### DB 접근 — 경계에서는 JDK 타입만

```java
// runtime-api
public interface SqlPort extends RuntimePort {
	List<Map<String, Object>> query(String sql, Map<String, Object> params);
	Optional<Map<String, Object>> queryOne(String sql, Map<String, Object> params);
	int update(String sql, Map<String, Object> params);
}
```

runtime은 `JdbcClient`로 구현하고, **행 → 도메인 객체 매핑은 app 안에서** 한다.

> **주의.** `BeanPropertyRowMapper`를 app class에 쓰지 않는다. Spring의 `CachedIntrospectionResults`가 app `Class`를 static cache에 잡는다. docs 14장 8번(`CachedIntrospectionResults.clearClassLoader`)이 미구현이라 그대로 샌다.

JPA는 부적합하다. `EntityManagerFactory`가 entity class·metamodel·바이트코드 프록시를 영구 보유하므로 entity를 app에 두면 배포마다 metaspace가 증가한다. **entity를 runtime에 고정하고 app은 SQL/port만 쓰거나, JPA를 쓰지 않는다.**

#### Port 세트

| Port | 용도 | 주의 |
|---|---|---|
| `TransactionPort` | 트랜잭션 경계 | 콜백 스타일 유지 |
| `SqlPort` | DB 접근 | 반환 타입은 JDK 타입만 |
| `CachePort` | Redis/Caffeine | key/value는 `String`·`byte[]` |
| `HttpClientPort` | 외부 API | 응답을 `String`/`Map`으로 |
| `ExecutorPort` | 비동기 | **lease 연동 필수**(아래) |
| `ConfigPort` | app별 설정 | `Environment` 직접 노출 금지 |
| `ClockPort`, `IdPort` | 테스트 용이성 | — |
| `MetricsPort` | counter/timer | meter 이름에 배포 SHA 포함, unload 시 제거 |

> **`@Async` 경고.** lease는 HTTP 요청만 추적한다. app이 비동기 작업을 띄우면 lease 밖에서 실행되어 닫힌 context에 접근하거나 classloader를 붙잡는다. `ExecutorPort`는 **제출 시점에 lease를 획득하고 완료 시 반환**하도록 구현한다. 그 구현 없이는 비동기를 금지한다.

### 2.3 서드파티 라이브러리

현재 코드에서 가장 먼저 막히는 지점이다. 제약이 둘이다.

1. `ArtifactValidator:120-127` — JAR 안의 모든 `.class`가 `allowed-class-prefixes`(현재 `my/spring/research/app/`) 밖이면 거절된다. **지금은 라이브러리를 shade해서 넣을 수 없다.**
2. `AppClassLoaderFactory` — URL이 artifact JAR 하나뿐이다. lib 디렉터리 개념이 없다.

#### 방식 A — parent 제공 (권장 기본값)

`AppClassLoader`가 parent-first이고 차단 목록에 없는 패키지는 위임하므로, **runtime classpath의 라이브러리는 app에서 이미 보인다.** 빌드 설정만 맞추면 된다.

```gradle
// runtime/build.gradle
implementation 'com.fasterxml.jackson.core:jackson-databind'
implementation 'org.apache.commons:commons-lang3:3.17.0'

// app/build.gradle
compileOnly project(':runtime-api')
compileOnly 'com.fasterxml.jackson.core:jackson-databind'   // JAR에 포함되지 않는다
compileOnly 'org.apache.commons:commons-lang3:3.17.0'
```

- 장점: JAR가 작고, 검증 규칙 변경이 없고, class가 한 벌만 로딩된다.
- 단점: 버전이 runtime에 고정된다. app만 버전을 올릴 수 없다(재기동 필요).

> **정적 캐시를 가진 라이브러리 주의.** `ObjectMapper`를 parent singleton으로 공유하고 app DTO를 직렬화하면 serializer 캐시가 app `Class`를 잡는다(docs 6장). Jackson `TypeFactory.defaultInstance()`도 static cache다.
>
> **규칙: 라이브러리 _class_ 는 공유하되 _인스턴스_ 는 slot이 소유한다.** app이 자기 `ObjectMapper`를 생성해 `components()`에 등록하면 `context.close()`와 함께 버려진다. Lombok, MapStruct처럼 컴파일 타임에만 존재하는 것은 문제가 없다.

#### 방식 B — app JAR에 shade

배포마다 다른 버전이 필요하거나 정적 캐시를 slot과 함께 버려야 할 때만 쓴다.

```yaml
runtime:
  hot-deploy:
    allowed-class-prefixes:
      - my/spring/research/app/
      - my/spring/research/shaded/     # shadowJar relocate 대상
    max-jar-entries: 50000
    max-uncompressed-bytes: 268435456
```

`shadowJar`로 `com.google.common` → `my.spring.research.shaded.guava` 식으로 relocate한다. relocate 없이 넣으면 parent class와 섞여 `ClassCastException`이 난다. 배포마다 metaspace 증가폭이 커지므로 반복 배포 endurance 검증을 함께 붙인다.

**권장 하이브리드:** port 경계에 등장하는 타입을 만드는 라이브러리는 parent 고정, app 내부에서만 도는 순수 계산 라이브러리만 shade를 허용한다.

#### Spring 서드파티(spring-data, spring-tx 등)

**app JAR에 넣을 수 없다.** `ArtifactValidator:107-118`과 `AppClassLoader`가 `org/springframework/`를 이중으로 차단한다. Spring 라이브러리는 전부 runtime에 두고 app에는 **port로만 노출**하는 것이 이 아키텍처의 유일한 정합적 사용법이다.

### 2.4 app에서 Spring annotation을 열 것인가

`RuntimeAdvisorOnAppBeanProbeTest`가 실측을 끝냈다.

- **BPP는 parent → child로 상속되지 않는다.** `CandidateContextFactory`가 `AnnotationConfigUtils.registerAnnotationConfigProcessors()`를 호출하지 않아 **BeanPostProcessor가 0개**다. classloader만 열면 annotation은 로딩되지만 **조용히 무시된다.**
- **Advisor는 parent에 둬도 된다.** `BeanFactoryAdvisorRetrievalHelper`가 `beanNamesForTypeIncludingAncestors()`로 부모까지 탐색한다(프로브 2번).

| 요소 | 위치 | 근거 |
|---|---|---|
| auto-proxy creator (BPP) | **child 필수** | parent에만 있으면 app bean은 프록시조차 되지 않는다 |
| Advisor / Interceptor / Aspect | **parent 가능** | ancestor 탐색이 동작한다 |
| annotation | `runtime-api` | parent-loaded라 identity 문제가 없다 |

열려면 정확히 두 가지를 고쳐야 한다.

1. **`AppClassLoader`에 AOP 패키지 공유 추가** — `org.springframework.aop.`, `org.aopalliance.`, (CGLIB 사용 시) `org.springframework.cglib.`. app 소스가 Spring 무의존이어도 **생성된 프록시가 `SpringProxy`·`Advised`를 구현**하므로 보이지 않으면 `... is not visible from class loader`로 실패한다(프로브 1번). 컴파일 의존성과 런타임 가시성은 별개다.
2. **`CandidateContextFactory:58`의 조회 방식** — JDK 프록시는 구체 타입을 상속하지 않아 `getBean(entryPoint)`가 `NoSuchBeanDefinitionException`으로 깨진다(프로브 2번). **`proxyTargetClass=true`(CGLIB)로 강제**하면 프록시가 `entryPoint`의 서브클래스라 기존 조회가 살아남으므로 변경 폭이 가장 작다.

**기능별 위험도**

| 기능 | 판정 |
|---|---|
| 커스텀 `@Aspect`, `@Transactional`, `@Cacheable` | 프록시·advisor를 child에 가두면 관리 가능 |
| `@Scheduled`, `@EventListener` | 등록 위치를 틀리면 parent 레지스트리가 app 객체를 영구 보유 |
| `@Async` | **drain 설계와 정면 충돌. 열지 않는다** |
| Spring Data JPA | **부적합**(2.2 참조) |

**권장 방향은 Spring annotation 개방이 아니라 자체 annotation이다.**

```java
// runtime-api
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Transactional {
	Propagation value() default Propagation.REQUIRED;
}
```

runtime이 대응 `Advisor`를 parent에 정의하고, 배포마다 child에 등록하는 것은 `DefaultAdvisorAutoProxyCreator`뿐이다. 그러면 app은 Spring 무의존을 유지하면서 선언적 사용감을 얻고, runtime이 **어떤 횡단 관심사를 열지 통제**한다. `@Async`처럼 위험한 것이 실수로 들어올 여지가 없다.

> 상태(method cache)를 가진 advisor는 app `Method`를 붙잡으므로 parent가 아니라 **child에 등록**한다.

### 2.5 커넥션풀 고정 정책

- `spring.datasource.hikari.*`는 runtime `application.yaml`에 두고 **`refresh-allowlist`에 넣지 않는다.** `DataSource`가 `@RefreshScope`가 아니면 반영되지 않고, `@RefreshScope`로 만들면 in-flight 커넥션이 끊어진다.
- 운영은 **두 트랙**이다: 인프라 변경 = 계획된 재기동(롤링), 로직 변경 = hot deploy.
- 무중단 풀 조정이 꼭 필요하면 HikariCP의 `HikariConfigMXBean`이 `maximumPoolSize`·`idleTimeout` 등 일부를 런타임 변경 지원한다. 이 경우 refreshable key 추가 규칙대로 **세 곳을 함께 고친다.**
  1. `application.yaml`의 `runtime.hot-deploy.refresh-allowlist`
  2. `RuntimeRefreshCoordinator.SUPPORTED_KEYS`
  3. `RuntimeRefreshCoordinator.validateValue()`의 switch

  그리고 coordinator가 `MapPropertySource` 교체 후 MXBean을 호출하도록 추가한다. 셋 중 하나라도 빠지면 key가 거절된다.

---

## 3. 운영 규약

app 개발 규칙으로 문서화하고 리뷰에서 강제한다.

- app 리소스는 전부 `AutoCloseable`로 통일한다. **`@PreDestroy`는 `CommonAnnotationBeanPostProcessor`가 등록되지 않아 무시된다.** `close()`만 destroy method로 추론된다.
- unmanaged thread/executor 생성 금지. 비동기는 `ExecutorPort`만 사용한다.
- JVM shutdown hook, global static registry, system property 변경 금지.
- mutable static singleton 금지.
- app class를 담은 `ThreadLocal`은 호출 종료 전에 제거한다.
- app 정의 DTO를 응답 경계로 반환 금지. `BusinessResponse`로 평탄화한다.
- 새 app package를 추가하면 `runtime.hot-deploy.allowed-class-prefixes`에 등록한다.

---

## 4. 투입 전 필수 작업

**현재 프로브가 통과하는 것은 sample app이 아무 리소스도 잡지 않기 때문이다.** 실제 서비스 계층을 넣는 순간 프로브는 초록불인 채로 실제로는 샌다. `LoadedCandidate.close()`는 `context.close()` + `classLoader.close()`가 전부이며 docs 14장의 5~8번이 미구현이다.

| # | 작업 | 위치 |
|---|---|---|
| 1 | `CachedIntrospectionResults.clearClassLoader(cl)` + `Introspector.flushCaches()` | `LoadedCandidate.close()` |
| 2 | app TCCL을 가진 thread 잔존 검사, `ThreadLocal` 정리 | `LoadedCandidate.close()` |
| 3 | app 등록 JDBC driver / MBean / shutdown hook 제거 | `LoadedCandidate.close()` |
| 4 | 배포별 meter·health·listener 레지스트리 해제 | `DeploymentManager` |
| 5 | 반복 배포 endurance 테스트 (500회 배포 후 metaspace·loaded class 수 비선형 증가 없음) | 신규 |
| 6 | `ExecutorPort` — lease 연동 없이는 비동기 금지 | `runtime-api` + `DeploymentSlot` |

1~5번이 끝나기 전에는 리소스를 잡는 서비스 계층을 app에 넣지 않는다.

---

## 5. 남은 확장 과제

- **단일 slot 구조.** `DeploymentRouter.active`는 하나뿐이고 진입점도 `POST /api/business` 하나다. 도메인이 여러 개면 `appId`별 slot map과 `AppModule`의 route 메타데이터가 필요하다.
- **버전 스큐.** app이 `compileOnly`로 컴파일한 라이브러리 버전과 runtime 실행 버전이 다르면 `NoSuchMethodError`가 런타임에 발생한다. 공통 Gradle platform/BOM을 만들어 두 모듈이 같은 것을 import하게 한다.
- **`runtime-api` 비대화.** 여기 올린 것은 전부 hot deploy 불가 영역이다. port(runtime 구현 → app 호출)는 늘려도 되지만, app이 구현하는 진입점 interface는 소수로 유지한다.
- **artifact 전자서명 검증, 영속 audit/metric** — docs 2장의 운영화 후속 작업.

---

## 6. 결정 요약

| 질문 | 결정 |
|---|---|
| 왜 hot deploy가 되는가 | 교체 단위가 class가 아니라 `classloader + child context + slot`이고, 전환이 read/write lock으로 원자화되며, 옛 단위 수명이 lease 카운트로 관리되기 때문 |
| 커넥션풀 | runtime 고정. 변경은 재기동. 무중단이 필요하면 `HikariConfigMXBean` + refresh allowlist 3곳 수정 |
| Spring 기능 | app에 직접 넣지 않는다. `RuntimePort`로 노출하고, 선언적 사용감이 필요하면 `runtime-api` 자체 annotation + child의 auto-proxy creator |
| 서드파티 | parent 제공 + `compileOnly`가 기본. 인스턴스는 slot 소유. 버전 격리가 필요할 때만 relocate shading + `allowed-class-prefixes` 확장 |
| 지금 실무 투입 | 불가. 4장 1~5번 선행 필요 |
