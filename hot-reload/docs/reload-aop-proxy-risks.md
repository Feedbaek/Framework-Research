# hot-reload: AOP·프록시 위험 케이스

> 당시 조사 기록이다. 현재 정책은 [hybrid-reload-spec.md](hybrid-reload-spec.md)를 따른다. 메서드 검증은 세대별 후처리기를 추가해 기본 회귀 테스트에 포함했다.

2026-09-28 기준

가장 위험한 것은 오류 없이 조용히 꺼지는 기능입니다. `@PreAuthorize` 미적용, 자식 Filter 미등록, `@Async`/`@Scheduled`/`@KafkaListener` 무시가 여기에 해당합니다. 그다음은 재로딩 뒤의 `ClassCastException`(CCE)과 이전 세대 클래스로더 누수입니다.

대상은 이 저장소의 `reload` 엔진(Spring Boot 3.5.16)입니다. 엔진 코드(`child/`, `generation/`, `autoconfigure/`)와 README를 읽고 정리했으며, 실행해서 확인한 항목은 없습니다.

- **[확인]**: 이 저장소 코드에서 원인을 직접 확인한 항목
- **[추정]**: Spring이나 라이브러리 동작에서 추정한 항목. 테스트로 확인해야 합니다

## 0. 문제가 생기는 다섯 가지 원인

1. **BeanPostProcessor는 context마다 따로 동작합니다.** 부모의 BPP는 자식 bean에 적용되지 않습니다. 부모의 `Advisor` bean은 `ChildAspectJAutoProxyCreator.isEligibleAdvisorBean`이 막고, 부모 `@Aspect`만 자식에 적용됩니다.
2. **이벤트는 자식에서 부모 방향으로만 전파됩니다.**
3. **부모 클래스로더도 같은 `build/classes/java/main`을 봅니다.** 부모 쪽 코드가 클래스 이름으로 로드하면 자식 클래스의 유령 사본이 생기고, `Foo cannot be cast to Foo`가 납니다.
4. **Tomcat, HttpSession, 부모 bean은 세대보다 오래 삽니다.** 여기에 자식 객체가 들어가면 이전 세대가 수거되지 않고, 재로딩 뒤에는 CCE가 납니다.
5. **자식에는 Boot auto-configuration이 없습니다.** 부모의 auto-configuration은 자식 bean을 보지 못합니다.

## 1. 자식 bean에서 조용히 꺼지는 AOP·어노테이션 기능

부모에 `@Enable*`가 있어도 엔진이 자식에 다시 켜 주는 것은 `@Transactional`과 `@Cacheable`뿐입니다. Advisor나 BPP로 구현된 기능은 자식 bean에서 오류 없이 빠집니다.

| 기능 | 구현 방식 | 부모에 `@Enable*`가 있을 때 자식 bean에서 |
| --- | --- | --- |
| `@Transactional`, `@Cacheable` | 엔진이 자식에 다시 켬 | 동작함 [확인] |
| `@PreAuthorize`, `@Secured` (`@EnableMethodSecurity`) | Advisor bean | **적용 안 됨. 권한 검사 없이 통과하는 보안 구멍** [추정] |
| `@Retryable` (`@EnableRetry`) | `RetryConfiguration`이 Advisor | 적용 안 됨 [추정] |
| ShedLock proxy 모드, 사용자 정의 Advisor | Advisor | 적용 안 됨 |
| `@Async` | BPP | 적용 안 됨. 동기로 실행 |
| `@Scheduled` | BPP | 실행 안 됨 |
| `@Validated` 메서드 검증 | `MethodValidationPostProcessor`(BPP) | 적용 안 됨. 컨트롤러 `@Valid @RequestBody`는 MVC가 처리해서 동작 |
| `@KafkaListener`, `@RabbitListener`, `@JmsListener` | BPP | 리스너 미등록. 메시지를 소비하지 않음 |
| `@Timed`, `@Observed`, Resilience4j | `@Aspect` bean | 적용됨. 대신 aspect 상태 누수 위험(2장) |

### 자식 패키지에서 `@Enable*`로 직접 켰을 때

- **`@EnableScheduling`**: 이전 세대는 drain이 끝날 때까지(최대 `drain-timeout` 30초) 살아 있습니다. 그동안 스케줄 작업이 두 세대에서 중복 실행됩니다.
- **`@EnableAsync`**: 부모의 `applicationTaskExecutor`를 씁니다. 비동기 작업은 drain 대상이 아니라서 작업 도중 세대가 dispose될 수 있습니다. 또 풀 스레드는 처음 만들어질 때 요청 스레드의 TCCL(세대 클래스로더)을 물려받고, 풀에 남아 그 세대를 붙잡습니다. [확인: 8장]
- **`@EnableKafka`**: 세대마다 리스너 컨테이너가 시작하고 멈춰 consumer group 리밸런스가 일어납니다. drain 동안에는 컨슈머가 두 벌입니다.

## 2. 프록시와 Aspect 적용 범위

- **프록시 방식이 강제로 고정됩니다 [확인]**
  - `ChildInfrastructureConfiguration.java:43,50`이 `proxyTargetClass = true`로 고정돼 있습니다. 부모에 트랜잭션 매니저나 캐시가 있으면 `spring.aop.proxy-target-class=false`가 자식에서 무시됩니다.
  - `reload.enabled=false`(운영)에서는 JDK 프록시가 쓰입니다. 구체 클래스 타입으로 주입받는 코드가 개발에서는 되고 운영에서는 실패할 수 있습니다.
- **Aspect가 한쪽 방향으로만 적용됩니다.** 부모 `@Aspect`는 자식 bean에 적용됩니다. 반면 자식 소유 `@Aspect`(기본 패키지에 두면 자식 소유)는 `parent-packages`의 서비스에 적용되지 않습니다. 로깅이나 감사 aspect가 절반의 bean에만 적용됩니다.
- **부모 aspect가 자식 소유 어노테이션을 참조하는 경우**(`@annotation(com.x.web.Audit)`): 부모가 `Audit`의 유령 사본을 로드해서 매칭이 실패하고, aspect가 조용히 적용되지 않습니다. BoundaryChecker 경고 대상이며, 커스텀 어노테이션은 `parent-packages`에 둡니다.
- **부모 aspect 인스턴스의 상태**: 필드에 `Map<Method, …>`, `Class`를 키로 쓰는 카운터, 보관한 `JoinPoint`가 있으면 누수가 됩니다. 기존 `AopLeakWithParentAspectOnlyTest`는 상태가 없는 aspect만 검증합니다.
- reload와 무관한 Spring 일반 제약: final 클래스·메서드, private 메서드, self-invocation은 프록시로 가로챌 수 없습니다.

## 3. 스코프 프록시와 세션: 재로딩 뒤 CCE

- **`@SessionScope` bean**: 세션의 `scopedTarget.xxx` 속성에 이전 세대 인스턴스가 남습니다. 새 세대 프록시가 이 값을 꺼내면 CCE가 나고, 세션이 만료될 때까지 이전 클래스로더가 수거되지 않습니다. [추정]
- **자식 객체를 세션에 넣는 경우**: `HttpSession.setAttribute`, `@SessionAttributes`, Flash attribute에 자식 타입 객체를 넣어도 같은 문제가 생깁니다.
- **Spring Security principal**: principal이 자식 클래스(예: 기본 패키지의 `CustomUserDetails`)면 재로딩 뒤 캐스팅이 실패하고, `@AuthenticationPrincipal`에 `null`이 들어옵니다. [추정]
- **Spring Session(Redis/JDBC)의 JDK 직렬화**: 역직렬화가 부모 클래스로더로 일어나 유령 클래스가 됩니다.
- **부모에 등록된 커스텀 스코프**(`refresh`, Spring Batch의 `step`/`job` 등): 자식 bean factory가 상속하지 않아 `No Scope registered for scope name ...`로 기동이 실패합니다. [추정]
- 대응: 세션에 넣는 타입은 `parent-packages`에 두거나, 재로딩 시 세션을 무효화합니다.

## 4. 캐시·직렬화·유령 클래스

- **`@Cacheable` 값이나 키가 자식 타입이고, CacheManager가 부모의 in-memory 캐시인 경우**: 누수에 더해, 재로딩 뒤 캐시 히트가 이전 클래스의 인스턴스를 돌려줘 CCE가 납니다. README는 누수만 언급합니다.
- **Redis 캐시 직렬화**
  - JDK serializer: 부모 클래스로더로 역직렬화해서 유령 클래스가 됩니다.
  - Jackson `@class` 방식: TCCL을 쓰므로 요청 스레드에서는 되고, 스케줄러나 리스너 같은 백그라운드 스레드에서는 유령 클래스가 됩니다. [추정]
- **자식 코드가 `@Autowired ObjectMapper`로 부모 인스턴스를 직접 쓰는 경우** [확인/추정]
  - MVC 컨버터만 `copy()`한 인스턴스를 씁니다. 직접 주입받은 부모 인스턴스에는 자식 클래스용 serializer/deserializer 캐시가 쌓입니다.
  - `GenerationCacheCleaner.java:37`은 `TypeFactory` 캐시만 비웁니다.
  - 부모에 정의된 `RestClient`/`RestTemplate`, `RedisTemplate`, Kafka `JsonSerializer`도 내부적으로 부모 `ObjectMapper`를 써서 같은 문제가 있습니다.
- **부모의 `Validator` bean으로 자식 DTO를 검증하는 경우**: Hibernate Validator 메타데이터 캐시에 자식 클래스가 남습니다. [추정]
- **부모 쪽 스캐너가 자식 패키지를 스캔하는 경우** [추정]
  - 해당: Spring Data 리포지토리, MyBatis `@Mapper`와 `typeAliasesPackage`, `@FeignClient`, `@ConfigurationPropertiesScan`, `@ServletComponentScan`
  - 이 스캐너들은 컴포넌트 스캔용 `TypeExcludeFilter`를 거치지 않을 수 있습니다.
  - 그러면 부모가 유령 사본을 등록하고, 자식에서는 타입이 맞지 않아 `NoSuchBeanDefinitionException`이 나거나 재로딩이 되지 않습니다. MyBatis 결과 객체가 유령 클래스로 만들어질 수도 있습니다.

## 5. 이벤트와 라이프사이클

- **부모에서 자식으로는 이벤트가 전파되지 않습니다.** 자식의 `@EventListener`와 `@TransactionalEventListener`는 다음 이벤트를 오류 없이 받지 못합니다.
  - `ApplicationReadyEvent`
  - `WebServerInitializedEvent`, `AvailabilityChangeEvent`
  - 부모 소유 서비스가 발행한 도메인 이벤트
  - Security의 `AuthenticationSuccessEvent`
- **자식의 `ApplicationRunner`/`CommandLineRunner`는 실행되지 않습니다.** `SpringApplication`은 부모 context의 runner만 호출합니다. [추정]
- **세대 lifecycle 이벤트는 부모로 보내지 않습니다.** 세대 자신의 `ContextRefreshedEvent`/`ContextClosedEvent` 등은 세대 안에서만 발행됩니다. 부모 리스너 중 출처를 확인하지 않는 것이 이를 애플리케이션 종료로 처리하기 때문입니다(JEUS starter의 `JEUSFinalizer`가 TM 서버를 내리면서 HTTP와 함께 쓰는 `jeus` 리스너가 닫히고, ProObject가 master 등록을 해제함). [확인: `EventsAndLifecycleScenarioTest.generationLifecycleEventsDoNotReachParentListeners`]
- **자식에서 부모로 가는 이벤트**: 부모 리스너가 이벤트 객체를 보관하면 누수가 됩니다. 또 `publishEvent(Object)`로 보낸 payload 이벤트는 부모 `applicationEventMulticaster`의 `retrieverCache`에 `PayloadApplicationEvent<자식 타입>` 키로 남아 모든 이전 세대를 붙잡습니다. [확인: 8장]
- **`@PostConstruct`는 세대마다 다시 실행됩니다.** 여기서 외부에 등록하면 등록이 쌓이고 이전 세대가 수거되지 않습니다.
  - 대상: Micrometer gauge, JMX, 정적 레지스트리, 부모 bean에 추가하는 리스너·콜백, shutdown hook
  - Micrometer는 같은 이름의 meter를 처음 등록한 것으로 유지합니다. 새 세대의 gauge는 무시되고 이전 세대 객체의 값이 계속 보고됩니다.
- **context close로 정리되지 않는 자원**: 자식에서 직접 만든 `Thread`, `Executors`, `Timer`와 static `ThreadLocal`은 context를 닫아도 멈추거나 비워지지 않아 누수가 됩니다.
- **부모 소유 `@Configuration`을 자식 설정에서 `@Import`하는 경우**: 스캔 제외를 우회하므로 DataSource나 커넥션 풀 같은 bean이 부모와 자식에 두 벌 만들어집니다.

## 6. 웹 계층

자식에 Boot auto-configuration이 없어서 생기는 문제입니다.

- **자식 패키지의 `Filter` bean, `FilterRegistrationBean`, `HttpSessionListener`는 Tomcat에 등록되지 않습니다.** 인증, CORS, 로깅 필터가 조용히 빠집니다.
- **Security 설정 클래스를 기본 패키지(자식 소유)에 두는 경우**
  - 부모에는 사용자 정의 `SecurityFilterChain`이 없어 Boot 기본 체인(모든 요청 인증, 자동 생성 비밀번호)이 적용됩니다.
  - 자식에 만든 체인은 어디에도 연결되지 않습니다.
  - 대응: Security 설정은 `parent-packages`에 두고, 거기서 참조하는 `UserDetailsService` 같은 타입도 함께 부모로 옮깁니다.
- **Security `requestMatchers(String)`** [추정]: 부모에 `mvcHandlerMappingIntrospector`가 없고 `api` 모드에서는 서블릿이 두 개입니다. Security 버전에 따라 기동이 실패하거나 Ant 매처로 fallback할 수 있습니다.
- **파일 업로드** [확인/추정]: `ReloadAutoConfiguration.java:51`의 서블릿 등록에 multipart 설정이 없습니다. `MultipartFile` 수신이 실패하고 `spring.servlet.multipart.*` 설정이 무시될 것으로 봅니다.
- **라이브러리가 제공하는 웹 엔드포인트** [추정]: 부모에 `@Controller` bean으로 등록되는 것(springdoc, Spring Boot Admin, ProObject `/proobject/system/**` 등)은 아래와 같이 세대 매핑이 처리합니다. 자체 핸들러 매핑을 쓰는 actuator HTTP 엔드포인트(`DispatcherServlet` bean을 조건으로 함)는 부모에 MVC가 없으므로 404가 날 수 있습니다.
- **부모의 `@Controller`(라이브러리, `parent-packages`)도 세대가 매핑합니다.** 세대 핸들러 매핑은 `detectHandlerMethodsInAncestorContexts=true`로 부모 컨트롤러를 찾고, 세대의 어댑터(ProObject 포함)로 처리합니다. 세대가 부모 bean을 참조하는 방향이라 누수가 없습니다. 부모와 자식 컨트롤러가 같은 매핑을 가지면 세대 생성이 실패합니다(일반 앱의 기동 실패와 같음). 부모 컨트롤러 코드를 바꾸면 전체 재시작입니다. [확인: `WebLayerScenarioTest.libraryControllerRegisteredInParentIsMapped`]
- **운영(`reload.enabled=false`)과 동작이 다른 Boot MVC 기본값**
  - `classpath:/static` 정적 리소스 제공
  - `/error`의 `BasicErrorController`
  - `spring.mvc.*` 설정
  - 추가 `HttpMessageConverter` bean(`ChildWebMvcConfig`가 컨버터 목록을 고정함)
  - Boot 변환기(`10s` → `Duration` 등)
- **WebSocket** [추정]
  - 연결 중인 세션은 drain 대상이 아니라서, 세대가 dispose된 뒤에도 이전 핸들러로 계속 동작하고 누수가 됩니다.
  - `@ServerEndpoint`는 컨테이너에 경로가 남아 있어, 두 번째 세대가 등록하면 "Multiple Endpoints ... same path"로 실패할 가능성이 큽니다.
- **SSE, 무한 timeout 비동기 요청**: `drain-timeout`까지 이전 세대가 유지되다 강제 dispose됩니다. 그 뒤 백그라운드에서 emitter로 보내면 이미 닫힌 context를 쓰게 됩니다.
- **auto-configuration 커스터마이징을 기본 패키지(자식 소유)에 둔 경우**
  - 대상: `ObjectMapper`, `Jackson2ObjectMapperBuilderCustomizer`, `WebServerFactoryCustomizer`, `CacheManager`, `RedisTemplate` 등
  - 부모 auto-configuration이 이 bean들을 보지 못해 물러나지 않고(back off 안 함), 커스터마이징도 적용되지 않습니다.
  - 같은 타입이 두 벌이 되어 단일 주입에서 `NoUniqueBeanDefinitionException`이 날 수 있습니다.

## 7. 우선 대응

### 위험 순위

1. 조용히 꺼지는 기능: `@PreAuthorize` 미적용, 자식 Filter 미등록, Security 설정 소유권, `@Async`/`@Scheduled`/`@*Listener` 무시, 부모 이벤트 미수신
2. 재로딩 뒤 CCE: `@SessionScope`, 세션 속성, Security principal, `@Cacheable` 값
3. 누수: 부모 `ObjectMapper` 직접 사용, 부모 aspect 상태, `@PostConstruct` 외부 등록

### 엔진 개선 후보

1. `ChildAspectJAutoProxyCreator`가 건너뛴 부모 Advisor 목록을 WARN 로그로 남긴다.
2. 자식 bean에 `@Async`/`@Scheduled`/`@*Listener`가 있는데 자식 context에 해당 BPP가 없으면 경고한다.
3. 자식 context에 `Filter`, `ServletContextInitializer`, `*Runner` bean이 있으면 경고한다.
4. 세대 교체 hook을 두어 캐시 evict와 세션 정리를 할 수 있게 한다.
5. multipart 설정을 서블릿 등록에 적용한다.

## 8. 시나리오 테스트 결과

2026-09-28, `reload` 모듈의 `*ScenarioTest`로 확인했습니다. 각 테스트는 "일반 Spring Boot 앱이라면 기대하는 동작"을 검증합니다. 현재 엔진에서 실패하는 테스트에는 `@KnownIssue`가 붙어 있습니다.

```bash
./gradlew :reload:test             # 통과해야 하는 시나리오 (known-issue 제외)
./gradlew :reload:knownIssueTest   # 알려진 문제 (모두 실패하는 것이 현재 상태)
```

### 문서에 없던 새 발견

| 문제 | 증상 | 테스트 |
| --- | --- | --- |
| `spring-boot-starter-actuator` 추가 시 기동 실패 | actuator의 `WebMvcServletEndpointManagementContextConfiguration`이 `DispatcherServletPath` bean을 요구하는데 엔진이 `DispatcherServletAutoConfiguration`을 제외함 | `ActuatorScenarioTest` |
| 부모 스레드 풀이 세대를 붙잡음 | `task-N`, `scheduling-1` 스레드가 생성 시 세대 클래스로더를 TCCL로 물려받음 | `MethodInterceptionScenarioTest.childEnabled*ReleasesPreviousGenerations` |
| payload 이벤트 누수 | 부모 multicaster `retrieverCache`에 자식 타입이 남아 10개 세대 중 10개 잔존 | `EventsAndLifecycleScenarioTest.childEventsPublishedToParentDoNotPinPreviousGenerations` |
| `@Value("5s") Duration` 실패 | 자식 bean factory에 Boot 변환 서비스가 없어 새 세대가 뜨지 못함 (`@ConfigurationProperties`는 정상) | `WebLayerScenarioTest.valueAnnotationUsesBootConversions` |

### 추정에서 확인으로 바뀐 것

- `requestMatchers(String)`을 쓰는 흔한 보안 설정은 기동 자체가 실패합니다(`mvcHandlerMappingIntrospector` 없음). `PathPatternRequestMatcher`로 바꾸면 동작합니다.
- `@PreAuthorize`: 권한 없는 사용자 요청이 403이 아니라 200으로 통과합니다.
- `@Async`는 동기로 실행되고, `@Scheduled`는 실행되지 않고, `@Validated` 메서드 검증은 빠집니다. 부모 `Advisor`와 자식 `@Aspect`(부모 bean 대상)도 적용되지 않습니다.
- 자식 `@EnableScheduling`을 쓰면 이전 세대가 drain되는 동안 두 세대의 작업이 함께 돕니다(500ms 동안 8회).
- `@SessionScope`, 세션 속성, `@Cacheable` 값은 재로딩 뒤 500이 납니다. 부모에 등록한 커스텀 스코프는 요청 시 `No Scope registered`로 실패합니다.
- 부모 `ObjectMapper`를 직접 쓰면 누수가 생기고, `copy()`한 복사본을 쓰면 생기지 않습니다. 부모 `Validator`도 누수가 생깁니다(Hibernate Validator 캐시는 SOFT 참조라 메모리 압박 전까지 잔존).
- 자식 Filter·`FilterRegistrationBean` 미적용, multipart(`no multi-part configuration`), 정적 리소스 404, Boot 오류 JSON 없음, 부모 `HttpMessageConverter` bean 미사용, 자식 Jackson customizer 미적용을 모두 확인했습니다. (라이브러리 컨트롤러 404는 세대 매핑이 부모 컨트롤러를 찾도록 해결했습니다.)
- 자식 패키지의 `SecurityFilterChain`은 적용되지 않고 Boot 기본 체인이 401을 돌려줍니다.
- gauge는 첫 세대 값에 고정되고(이후 NaN), 첫 세대를 붙잡습니다.

### 문제없이 동작한 것

- `@Transactional`, `@Cacheable`(세대 안), 자식 `@EnableAsync`로 켠 비동기 실행, 교체 시 이전 세대의 스케줄 작업 중지
- 요청·세션 스코프(세대 안), 자식 `ContextRefreshedEvent` 수신, 자식 이벤트의 부모 리스너 전달
- 부모 보안 필터 체인(재로딩 뒤 포함), `@AuthenticationPrincipal`, 부모 `WebMvcConfigurer` 인터셉터
- `@RestControllerAdvice`, `@Valid @RequestBody` 400, `spring.jackson.*` 설정, `@ConfigurationProperties`의 `Duration`·`List` 바인딩
- SSE 스트리밍 중 재로딩(처음 세대에서 끝까지 처리 후 dispose)

### 테스트하지 않은 것

JPA·Spring Data, MyBatis, Redis, Kafka, WebSocket, Spring Session은 의존성이 커서 제외했습니다. 부모가 자식 클래스의 유령 사본을 로드하는 경우(4장)는 9장의 `ConsumerApp` 배치에서 확인했습니다.

## 9. 부모 패키지에 순수 자바 코드만 두는 배치

2026-09-28, `PureJavaParentScenarioTest`로 확인했습니다. 부모 패키지(`reload.parent-packages`)에는 Spring 애너테이션이 없는 도메인 모델·계산 로직·유틸리티만 두고, 컨트롤러·서비스·설정 같은 Spring 코드는 모두 자식에 둡니다. 테스트는 실제 소비자 앱과 같은 배치로 띄웁니다. `@SpringBootApplication` 클래스와 부모·자식 코드를 한 출력 디렉터리에 컴파일하고, 부모 클래스로더도 그 디렉터리를 봅니다(`ConsumerApp`).

**결론: 부모 타입만 주고받는 한 잘 동작합니다.** 문제는 크게 세 가지입니다.

1. 부모 코드를 고치면 재시작해야 합니다. 그런데 `api` 모드는 이를 알려 주지 않고, 옛 버전과 새 버전이 섞일 수 있습니다.
2. 부모 클래스의 정적 상태에 자식 객체가 들어가면 누수나 CCE가 생깁니다.
3. 부모 코드가 이름이나 TCCL로 클래스를 찾으면 자식 클래스의 유령 사본이 생깁니다.

### 문제없이 동작한 것

| 시나리오 | 결과 |
| --- | --- |
| 자식 컨트롤러가 부모 도메인 로직·값 객체·enum 사용 (재로딩 전후) | 정상 |
| 부모 정적 저장소에 부모 타입(`Order`) 보관 후 재로딩 | 정상, 누수 없음 |
| 부모 인터페이스의 자식 구현체(`@Component`) 수정 후 재로딩 | 새 구현 반영 |
| 부모 추상 클래스를 상속한 자식 클래스 수정 후 재로딩 | 새 구현 반영 |
| 부모 도메인 예외를 자식 `@RestControllerAdvice`가 처리 | 정상 |
| 부모 record를 `@Valid @RequestBody`와 응답 본문으로 사용 | 정상(400 검증 포함) |
| 세션에 부모 타입 저장 후 재로딩 | 정상 (자식 타입일 때의 CCE 대응책) |
| watch 모드에서 부모 클래스 변경 | 재시작 경고가 나옴 |
| 부모 클래스가 자식 타입을 참조 | 시작 시 BoundaryChecker 경고가 나옴 |

### 문제가 확인된 것 (`@KnownIssue`)

| 문제 | 증상 |
| --- | --- |
| 부모 로직 변경이 반영되지 않음 | 계산 로직을 고치고 재로딩해도 옛 값(110)을 반환 (설계상 제약) |
| `api` 모드에서 경고 없음 | 부모 클래스가 바뀌었는데 경고 없이 `reloaded=true`를 반환 |
| 부모 코드의 옛 버전·새 버전 혼재 | 부모 클래스는 처음 쓰일 때 로드됨. 변경 전에 쓰인 클래스는 옛 버전, 안 쓰인 클래스는 새 버전이라 `NoSuchMethodError` |
| 부모 정적 레지스트리의 자식 리스너 | 재로딩 뒤 이전 세대 리스너도 계속 호출됨(1개 기대, 2개 호출). 10개 세대 모두 잔존 |
| 부모 정적 캐시의 자식 객체 | 재로딩 뒤 `CartView cannot be cast to CartView` |
| 부모 유틸의 `Class` 키 캐시 | 자식 클래스가 캐시에 남아 10개 세대 모두 잔존 |
| 부모 `ThreadLocal`에 넣고 안 비운 자식 객체 | Tomcat 스레드에 남아 10개 세대 모두 잔존 |
| 부모 정적 스레드 풀 | 풀 스레드가 세대 클래스로더를 TCCL로 물려받아 잔존 |
| 부모 코드의 `Class.forName` | 부모 클래스로더가 자식 클래스의 유령 사본을 만듦 |
| 부모 코드가 공용 ForkJoinPool에서 TCCL 사용 | 현재 세대 클래스를 찾지 못함 |
| 부모 클래스가 자식 타입을 참조 | 경고는 나오지만 실행 시 `LinkageError: loader constraint violation` |
| 애플리케이션 클래스의 package-private 멤버 | 같은 패키지의 자식 클래스가 호출하면 `IllegalAccessError` (런타임 패키지가 다름) |
| 자식 설정의 `@EnableCaching` | 부모 `CacheAutoConfiguration`이 보지 못해 `CacheManager`가 없고 첫 세대가 뜨지 못함(503) |

### 이 배치에서 지킬 규칙

- 부모 패키지 코드를 고치면 재시작합니다. `api` 모드는 경고가 없으므로 특히 주의합니다.
- 부모 클래스의 정적 필드(캐시, 레지스트리, `ThreadLocal`, 스레드 풀)에는 부모 타입이나 문자열·숫자만 넣습니다.
- 부모 코드에서 `Class.forName`, TCCL, `ServiceLoader`로 자식 클래스를 찾지 않습니다.
- 부모 코드가 자식 타입을 import하지 않습니다. BoundaryChecker 경고를 오류로 취급합니다.
- 애플리케이션 클래스에는 package-private 멤버를 두지 않습니다.
- `@EnableCaching`, `@EnableAsync` 같은 Boot 연동 설정은 애플리케이션 클래스(부모)에 둡니다.

## 10. 부모 `@Component` + 자식 AOP·설정 배치와 Advisor 위치

2026-09-29, `LayoutScenarioTest`로 확인했습니다. 모두 `ConsumerApp`으로 실제 소비자 앱과 같은 배치에서 띄웁니다.

**결론: 부모에 `@Component`, 자식에 AOP·설정을 두는 배치는 권장하지 않습니다.** AOP와 설정은 부모에서 자식 방향으로만 적용되므로, 자식에 둔 설정은 부모 bean에 전혀 닿지 않습니다. 반대 배치(부모에 인프라 설정과 `@Aspect`, 자식에 컨트롤러와 서비스)는 기능이 모두 정상 동작했습니다.

### 배치별 결과

| 기능 | 부모 `@Component` + 자식 AOP·설정 | 부모 인프라 + 자식 컨트롤러·서비스 (권장) |
| --- | --- | --- |
| `@Aspect` | 자식 bean에만 적용, 부모 bean에는 미적용 | 부모 aspect가 자식 bean에 적용 |
| `@Transactional` | 자식 bean도 미적용(아래 새 발견 1), 부모 bean 미적용 | 자식 bean에 적용 |
| `@Cacheable` | 자식 설정에 `CacheManager`까지 두면 자식 bean만 적용. 부모 bean 미적용 | 자식 bean에 적용 |
| `@Async` | 자식 bean만 적용. 부모 bean은 동기 실행 | (부모 `@EnableAsync`는 자식에 미적용, 1장) |
| 설정 bean 주입 | 부모 `@Component`가 자식 설정 bean을 주입받지 못해 **기동 실패** | 자식이 부모 설정 bean을 주입받음 |
| Jackson customizer | (8장: 자식 customizer 미적용) | 부모 customizer가 자식 MVC에 적용 |
| Filter | (8장: 자식 Filter 미등록) | 부모 Filter가 자식 요청에 적용 |
| 보안 | - | 부모 `SecurityFilterChain`이 자식 엔드포인트 보호, 자식 `@EnableMethodSecurity`로 `@PreAuthorize` 동작 |
| 재로딩 뒤 | 자식 인프라 유지 | 모두 유지 |

### Advisor 위치별 결과

| 배치 | 부모 bean | 자식 bean |
| --- | --- | --- |
| 부모 설정에 Advisor bean | 적용 | **미적용** (엔진이 설계상 차단) |
| 부모 애플리케이션 클래스에 `@EnableMethodSecurity` | - | **미적용**. `@PreAuthorize`가 권한 검사 없이 200 |
| Advisor 클래스는 부모 패키지, bean은 자식 설정(`@Bean`) | 미적용 | 적용. 재로딩 뒤에도 적용. 누수 없음(새 발견 2 수정 후) |
| Advisor 클래스는 부모 패키지, 자식 `@Component`로 상속 등록 | 미적용 | 적용. 누수 없음 |
| 자식 설정에 `@EnableMethodSecurity` | - | 적용 (403 확인) |

Advisor로 구현된 기능(`@PreAuthorize`, `@Retryable`, 사용자 정의 Advisor)을 자식 bean에 쓰려면 bean 등록을 자식에서 해야 합니다. 클래스는 부모 패키지에 있어도 됩니다.

### 새 발견

1. **자식 설정에 `TransactionManager`를 정의하면 자식 `@Transactional`도 켜지지 않습니다.** 엔진의 `ChildTransactionConfiguration`이 `@ConditionalOnBean(TransactionManager)`로 켜지는데, 자식 설정에 정의한 TransactionManager를 보지 못합니다. 일반 Boot 앱은 TransactionManager bean만 있으면 트랜잭션을 켜 줍니다. 자식 설정에 `@EnableTransactionManagement`를 직접 선언하면 동작합니다.
2. **[수정됨] 자식 `@Configuration`의 `@Bean` 메서드가 이전 세대를 붙잡았습니다.** Spring의 정적 캐시 `BeanAnnotationHelper.beanNameCache`가 `@Bean` 메서드를 키로 들고 있고, `GenerationCacheCleaner`가 비우지 않았습니다. `static` 여부, Advisor 여부와 관계없이 `@Bean` 메서드가 하나만 있어도 해당합니다. 자식에 설정 클래스를 두는 거의 모든 앱이 영향을 받습니다. 자식 `@EnableMethodSecurity`처럼 프레임워크가 자식에 등록하는 설정도 마찬가지입니다.
3. **[수정됨] 자식 클래스가 제네릭 인터페이스를 구현하면 이전 세대가 남았습니다.** `Supplier<T>`, `Converter<S, T>`, `ApplicationListener<E>` 등을 구현하면 컴파일러가 브리지 메서드를 만들고, `BridgeMethodResolver.cache`가 그 메서드를 들고 있습니다. 이 캐시는 공개 clear API가 없습니다.
4. **[수정됨] 자식 메서드에 `@PreAuthorize`, `@AuthenticationPrincipal` 같은 보안 애너테이션을 쓰면 이전 세대가 남았습니다.** Spring Security의 `SecurityAnnotationScanners`가 애너테이션별 스캐너를 정적 맵에 두고, 스캐너마다 메서드·파라미터 캐시를 가집니다. 2·3과 달리 강한 참조라 메모리 압박이 와도 풀리지 않는 누수였습니다.

2와 3은 SOFT 참조라서 `System.gc()`로는 풀리지 않지만, OOM 직전까지 메모리를 채우면 수거되는 것을 확인했습니다.

**수정 (2026-09-29)**: `GenerationCacheCleaner`가 세대 dispose 뒤에 다음 정적 캐시를 리플렉션으로 비웁니다. 모두 공개 clear API가 없습니다.

- `BeanAnnotationHelper.beanNameCache`, `scopedProxyCache`
- `BridgeMethodResolver.cache`
- `SecurityAnnotationScanners.uniqueScanners`, `uniqueTemplateScanners`, `uniqueTypesScanners` (Spring Security가 있을 때만)

Security 스캐너는 내부 캐시가 thread-safe하지 않을 수 있어 건드리지 않고, 스캐너를 담은 정적 맵만 비웁니다. 이전 스캐너를 들고 있던 객체는 이전 세대와 함께 사라지고, 새 세대는 새 스캐너를 받습니다. Spring을 올려 필드 이름이 바뀌면 정리가 조용히 건너뛰어지므로, `GenerationCacheCleanerTest`가 필드 존재와 정리 동작을 확인합니다.

수정 뒤 `childConfigurationBeanMethodsDoNotPinPreviousGenerations`, `childBeanImplementingGenericInterfaceDoesNotPinPreviousGenerations`, `recommendedLayoutDoesNotPinPreviousGenerations`, `advisorRegisteredInChildConfigurationDoesNotPinPreviousGenerations`가 통과해 `@KnownIssue`를 뗐습니다. `SecurityScenarioTest.authenticationPrincipalResolutionDoesNotPinPreviousGenerations`를 추가했습니다.

### 실험으로 좁힌 과정

권장 배치와 Advisor 배치의 누수 테스트가 처음에는 원인을 알 수 없게 실패했습니다. 아래 순서로 좁혔습니다. 실험 코드는 지웠고, 결론은 회귀 테스트로 남겼습니다.

| 조건 | 이전 세대 수거 |
| --- | --- |
| 보안만 켬(`SecurityFilterChain`, 인증 요청) | 됨 |
| 부모 `@Aspect`, 자식 `@Aspect`가 자식 컨트롤러를 프록시 | 됨 |
| 자식 설정의 `@Bean` Advisor (실제 배치 / 엔진 테스트 배치 모두) | 안 됨 |
| 자식 `@Component` Advisor | 됨 |
| 자식 설정의 `@Bean Supplier<String>` (Advisor 아님) | 안 됨 |
| 위 상태에서 `BeanAnnotationHelper` 캐시를 비움 | 됨 |
| 제네릭 인터페이스를 구현한 자식 `@Component` | 안 됨 (`BridgeMethodResolver.cache`) |
| 위 캐시를 비운 뒤, 권장 배치(자식 `@EnableMethodSecurity` + `@PreAuthorize`) | 안 됨 (`SecurityAnnotationScanners`) |

`LeakDiagnostics`는 이제 위의 정적 캐시도 모두 검사해서 실패 메시지에 보여 줍니다.

## 11. 설정 배치 규칙 도입

2026-09-29부터 엔진의 제약으로 둡니다: **인프라 설정은 부모에 두고, Advisor·BeanPostProcessor로 동작하는 `@Enable*`만 자식에 둔다.** 규칙 표와 검사 내용은 README의 "설정 배치 규칙"에 있습니다.

`ConfigurationPlacementChecker`가 위반을 찾고, `GenerationManager`가 경고합니다. 부모 쪽은 첫 세대를 만들 때 한 번, 자식 쪽은 위반 목록이 바뀐 세대마다 경고합니다.

| 검사 | 경고 대상 | 근거(이 문서) |
| --- | --- | --- |
| 부모 | `@EnableAsync`, `@EnableScheduling`, 트랜잭션·캐시 외의 `Advisor` bean(`@EnableMethodSecurity`, `@EnableRetry` 등) | 1장, 10장 Advisor 위치 |
| 자식 설정 | 허용 목록 밖의 `@Enable*`·`@Import`(`@EnableCaching`, `@EnableTransactionManagement`, `@EnableWebMvc`는 따로 안내), Advisor·BPP가 아닌 `@Bean` 메서드 | 6장, 10장 새 발견 1 |
| 자식 bean | Filter·서블릿 리스너·초기화 bean, Boot customizer, `SecurityFilterChain`, `CacheManager`, `TransactionManager`, `DataSource`, runner, `HttpMessageConverter` | 5장, 6장, 8장 |

이 규칙을 지켜도 남는 문제(1장의 자식 `@EnableAsync`/`@EnableScheduling` 스레드 풀 누수와 drain 중 중복 실행, 8장의 actuator·multipart·정적 리소스·오류 JSON 등 엔진 문제)는 `@KnownIssue` 테스트로 계속 추적합니다.

검증: `ConfigurationPlacementCheckerTest`(판정 단위 테스트 10개), `ConfigurationPlacementScenarioTest`(실제 배치에서의 경고, 준수 시 무경고, 변경 시에만 경고, 끄기). 샘플 앱(`sample-apps/greeting`)은 규칙을 지켜 경고가 없습니다.
