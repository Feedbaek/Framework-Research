# Spring Boot 3.x / Java 21에서 Spring DM·Virgo식 동적 모듈 구조를 구현한다면

## 1. 먼저 결론

Spring Boot 3.x / Java 21 환경에서 Spring DM이나 Virgo가 제공했던 구조를 그대로 가져오는 것은 현실적으로 어렵다.

특히 **실제 Spring DM 1.x/2.x를 Spring Boot 3에 결합하는 방식은 추천하기 어렵다.** 현재 Spring DM 문서에서 확인되는 안정 버전은 1.2.1이고, 2.0 계열도 Spring 3.x 시기의 기술을 대상으로 한다. 반면 Spring Boot 3.x는 Spring Framework 6.x를 사용하고 Java 17 이상과 Jakarta EE 9+ 기반이다.

따라서 현대 환경에서는 다음과 같이 접근하는 것이 더 현실적이다.

```text
Spring DM 자체를 사용
        X

Spring DM의 아키텍처를 참고
        ↓
Spring Boot 3.x
Java 21
독립 ClassLoader
독립 ApplicationContext
Service Registry
Dynamic Proxy
Module Lifecycle Manager
        ↓
직접 모듈 Runtime 구현
```

핵심 아이디어는 Spring DM과 동일하다.

> 하나의 거대한 Spring ApplicationContext를 여러 개의 독립적인 ApplicationContext와 ClassLoader로 분리하고, 모듈 사이의 연결을 직접적인 Bean 참조가 아니라 안정적인 API와 Service Registry를 통해 수행한다.

---

# 2. 목표 구조

기존 Spring Boot 애플리케이션이 다음과 같다고 가정하자.

```text
JVM
│
└─ Spring Boot
    │
    └─ ApplicationContext
        │
        ├─ Controller
        ├─ Service
        ├─ Repository
        ├─ MyBatis Mapper
        ├─ JPA
        ├─ Scheduler
        ├─ Converter
        ├─ Validator
        └─ 기타 수만~수십만 Bean
```

모든 Bean이 하나의 ApplicationContext에 있기 때문에 일부 기능만 수정해도 결국 전체 애플리케이션을 재기동해야 한다.

이를 다음 구조로 변경한다.

```text
JVM
│
├─ Core Runtime
│   │
│   ├─ Main Spring Context
│   ├─ ModuleManager
│   ├─ ServiceRegistry
│   ├─ ModuleRepository
│   └─ Shared Infrastructure
│
├─ Module A
│   ├─ ClassLoader A
│   └─ ApplicationContext A
│       ├─ Service
│       ├─ Repository
│       └─ ...
│
├─ Module B
│   ├─ ClassLoader B
│   └─ ApplicationContext B
│       ├─ Service
│       ├─ Repository
│       └─ ...
│
└─ Module C
    ├─ ClassLoader C
    └─ ApplicationContext C
        ├─ Service
        ├─ Repository
        └─ ...
```

이 구조에서는 Module B를 변경할 때:

```text
Module A    ACTIVE
Module B    UPDATE
Module C    ACTIVE
```

가 가능하도록 만드는 것이 목표다.

---

# 3. 가장 중요한 설계: ClassLoader 계층

가장 먼저 ClassLoader 구조를 설계해야 한다.

추천 구조는 다음과 같다.

```text
Bootstrap ClassLoader
        │
        ▼
Platform ClassLoader
        │
        ▼
Application / Core ClassLoader
        │
        ├─ Spring Framework
        ├─ Spring Boot
        ├─ Module API
        ├─ ServiceRegistry
        ├─ Common DTO
        └─ Shared Infrastructure
             │
             ├──────────────┐
             ▼              ▼
      ModuleClassLoader A   ModuleClassLoader B
             │              │
             ├─ impl A      ├─ impl B
             ├─ library A   ├─ library B
             └─ resources   └─ resources
```

중요한 것은 **모듈 사이에서 주고받는 타입을 Core ClassLoader가 로딩해야 한다는 점**이다.

예를 들어:

```java
public interface PaymentService {

    PaymentResult pay(PaymentRequest request);
}
```

다음 타입들은 Core 영역에 있어야 한다.

```text
PaymentService
PaymentRequest
PaymentResult
```

구현체만 Module ClassLoader에 둔다.

```text
core-api.jar
 └─ PaymentService

payment-module.jar
 └─ PaymentServiceImpl
```

이 구조가 매우 중요하다.

잘못해서 각 Module이 자신의 `PaymentService.class`를 포함하면:

```text
CoreClassLoader
PaymentService

ModuleClassLoader
PaymentService
```

JVM에서는 이름이 같아도 서로 다른 타입이다.

Java에서 클래스 identity는 사실상:

```text
Class Identity
=
Fully Qualified Class Name
+
ClassLoader
```

이기 때문이다.

따라서 다음 문제가 발생할 수 있다.

```java
PaymentService service =
    (PaymentService) moduleInstance;
```

겉으로는 같은 `PaymentService`인데도:

```text
ClassCastException
```

이 발생할 수 있다.

---

# 4. 모듈 API와 구현을 반드시 분리해야 한다

그래서 프로젝트 구조도 바뀌어야 한다.

기존:

```text
payment
├─ PaymentController
├─ PaymentService
├─ PaymentServiceImpl
├─ PaymentRepository
└─ PaymentDto
```

보다는 다음과 같이 나누는 것이 좋다.

```text
payment-api
│
├─ PaymentService
├─ PaymentRequest
└─ PaymentResult


payment-module
│
├─ PaymentServiceImpl
├─ PaymentRepository
├─ PaymentMapper
└─ PaymentConfig
```

그리고:

```text
Core Runtime
       │
       └── payment-api
                 ▲
                 │
        ┌────────┴────────┐
        │                 │
payment-module       order-module
```

처럼 만든다.

이 구조는 OSGi의:

```text
Export-Package
Import-Package
```

개념과 상당히 유사하다.

---

# 5. ModuleClassLoader

모듈마다 별도의 ClassLoader를 생성한다.

개념적으로는 다음과 같다.

```java
URLClassLoader moduleClassLoader =
        new URLClassLoader(
            new URL[] {
                moduleJar.toUri().toURL()
            },
            coreClassLoader
        );
```

하지만 실제 구현에서는 단순 `URLClassLoader`만으로 끝내기 어렵다.

다음 정책이 필요하다.

```text
java.*
spring.*
module-api.*
common.*
        ↓
Parent First

module implementation
module-specific library
        ↓
Child First
```

예를 들어:

```text
com.mycompany.api.*
org.springframework.*
jakarta.*
java.*
```

는 반드시 Parent에서 로딩한다.

반면:

```text
com.mycompany.payment.impl.*
```

은 Module ClassLoader가 로딩한다.

이런 정책을 만들지 않으면 Spring Framework 자체가 모듈별로 중복 로딩되는 상황이 발생할 수 있다.

---

# 6. 각 모듈마다 별도의 Spring ApplicationContext

그다음 핵심이 ApplicationContext다.

Main Context:

```text
MainApplicationContext

DataSource
TransactionManager
ModuleManager
ServiceRegistry
ObjectMapper
MeterRegistry
...
```

Module A:

```text
ModuleApplicationContext A

PaymentServiceImpl
PaymentRepository
PaymentMapper
...
```

Module B:

```text
ModuleApplicationContext B

OrderServiceImpl
OrderRepository
...
```

Spring 자체는 여러 `ApplicationContext`를 생성할 수 있고, `SpringApplication`도 사용할 ClassLoader와 ResourceLoader를 지정할 수 있는 구조를 갖고 있다.

개념적으로는 다음과 같이 만들 수 있다.

```java
AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

context.setClassLoader(moduleClassLoader);

context.setParent(mainApplicationContext);

context.register(moduleConfigurationClass);

context.refresh();
```

구조는:

```text
MainApplicationContext
        ▲
        │ parent
        │
ModuleContext A
```

가 된다.

그러면 Module에서는 Core Bean을 사용할 수 있다.

```java
@Autowired
DataSource dataSource;
```

하지만 반대로 Main Context가 Module Bean을 직접 알고 있도록 만들면 안 된다.

그 순간 hot unload가 매우 어려워지기 때문이다.

---

# 7. Parent → Module 직접 Bean 참조를 만들면 안 되는 이유

다음 구조는 위험하다.

```text
MainApplicationContext
        │
        │ reference
        ▼
PaymentServiceImpl
        │
        ▼
ModuleClassLoader
```

Module을 unload해도:

```text
Main Context
     ↓
PaymentServiceImpl
     ↓
Class
     ↓
ModuleClassLoader
```

reference가 남는다.

그러면:

```text
moduleClassLoader.close()
```

를 호출해도 ClassLoader가 GC되지 않는다.

결과:

```text
v1 ClassLoader
v2 ClassLoader
v3 ClassLoader
v4 ClassLoader
...
```

가 계속 heap에 남는다.

결국 Metaspace가 증가한다.

```text
Metaspace
██████████████
```

이것이 Java hot deployment에서 가장 무서운 문제 중 하나인 **ClassLoader Leak**이다.

---

# 8. Service Registry를 둬야 하는 이유

이를 해결하려면 Spring DM처럼 중간 계층을 둔다.

```text
Module A
PaymentServiceImpl
       │
       │ register
       ▼
ServiceRegistry
       │
       │ lookup
       ▼
Order Module
```

예를 들면:

```java
public interface ServiceRegistry {

    <T> void register(
        Class<T> type,
        T implementation
    );

    <T> T getService(Class<T> type);

    void unregister(Class<?> type);
}
```

Payment Module이 시작되면:

```java
registry.register(
    PaymentService.class,
    paymentServiceImpl
);
```

한다.

---

# 9. 하지만 Registry가 구현체를 직접 반환해도 부족하다

다음처럼 하면:

```java
PaymentService payment =
        registry.getService(PaymentService.class);
```

OrderService가 반환된 객체를 계속 가지고 있을 수 있다.

```text
OrderService
    │
    ▼
PaymentServiceImpl v1
```

Payment Module v2가 배포되어도:

```text
Registry
   ↓
PaymentServiceImpl v2
```

OrderService는 여전히:

```text
PaymentServiceImpl v1
```

을 참조한다.

그래서 Spring DM처럼 **Proxy**를 사용하는 것이 좋다.

```text
OrderService
     │
     ▼
PaymentService Proxy
     │
     ▼
ServiceRegistry
     │
     ▼
현재 PaymentService
```

v1:

```text
Proxy
  ↓
PaymentServiceImpl v1
```

업데이트:

```text
stop v1
start v2
```

이후:

```text
Proxy
  ↓
PaymentServiceImpl v2
```

가 된다.

OrderService 자체는 다시 생성할 필요가 없다.

---

# 10. 전체 Runtime 구조

결과적으로 Spring Boot 3.x에서 만들 수 있는 구조는 다음과 같다.

```text
┌─────────────────────────────────────────────┐
│                JVM / Java 21                │
│                                             │
│  ┌───────────────────────────────────────┐  │
│  │             Core Runtime              │  │
│  │                                       │  │
│  │ MainApplicationContext                │  │
│  │                                       │  │
│  │ ├─ ModuleManager                      │  │
│  │ ├─ ModuleRepository                   │  │
│  │ ├─ ServiceRegistry                    │  │
│  │ ├─ ProxyFactory                       │  │
│  │ ├─ DependencyResolver                 │  │
│  │ ├─ DataSource                         │  │
│  │ ├─ TransactionManager                 │  │
│  │ └─ Monitoring                         │  │
│  └───────────────────────────────────────┘  │
│                    │                        │
│          ┌─────────┼─────────┐              │
│          ▼         ▼         ▼              │
│       Module A   Module B   Module C         │
│          │         │         │              │
│       CL A      CL B      CL C               │
│          │         │         │              │
│       Context A Context B Context C          │
│                                             │
└─────────────────────────────────────────────┘
```

Spring DM을 상당히 단순화한 형태라고 볼 수 있다.

---

# 11. ModuleManager가 담당해야 하는 것

`ModuleManager`는 사실상 Virgo의 kernel과 비슷한 역할을 한다.

```java
interface ModuleManager {

    void install(Path module);

    void start(String moduleId);

    void stop(String moduleId);

    void update(String moduleId, Path module);

    void uninstall(String moduleId);
}
```

Lifecycle은 다음과 같다.

```text
INSTALLED
    ↓
RESOLVED
    ↓
STARTING
    ↓
ACTIVE
    ↓
STOPPING
    ↓
RESOLVED
```

업데이트는:

```text
ACTIVE v1
   ↓
STOPPING
   ↓
UNREGISTER SERVICES
   ↓
CLOSE CONTEXT
   ↓
CLOSE CLASSLOADER
   ↓
LOAD v2
   ↓
CREATE CONTEXT
   ↓
REGISTER SERVICES
   ↓
ACTIVE v2
```

가 된다.

---

# 12. 무중단 교체까지 하려면 Blue/Green 방식이 더 좋다

단순히:

```text
stop v1
start v2
```

하면 짧게나마 service unavailable 상태가 생긴다.

그래서 더 좋은 방법은:

```text
Payment v1
    │
    ▼
Service Proxy
```

상태에서 먼저 v2를 준비한다.

```text
Payment v1        Payment v2
    │                 │
 ACTIVE             STARTING
```

v2 초기화가 끝나면:

```text
Payment v1        Payment v2
    │                 │
 ACTIVE              READY
```

Registry pointer를 바꾼다.

```text
Service Proxy
      │
      └──────────────→ Payment v2
```

그 이후 v1을 종료한다.

```text
Payment v1
    ↓
DRAIN
    ↓
STOP
```

이 방식은 OSGi의 단순 bundle update보다 오히려 현대적인 운영 방식에 가깝다.

---

# 13. 중요한 문제: 기존 Spring 애플리케이션을 이렇게 바꾸는 것이 쉬운가?

여기서부터가 실제 핵심이다.

**새로 만드는 것보다 기존 Spring 애플리케이션을 이 구조로 변경하는 것이 훨씬 어렵다.**

Spring DM 공식 문서 자체도 OSGi 환경에서는 기존 enterprise library가 OSGi를 고려하지 않고 만들어졌을 때 별도의 문제가 발생한다고 명시하고 있다.

기존 애플리케이션에서는 보통 다음 구조가 매우 흔하다.

```text
Controller
   ↓
Service
   ↓
Service
   ↓
Repository
   ↓
Mapper

그리고 곳곳에서
@Autowired
@ComponentScan
static
reflection
AOP
JPA
MyBatis
Jackson
Scheduler
```

가 얽혀 있다.

이를 모듈 ClassLoader로 분리하면 숨어 있던 의존 관계가 전부 드러난다.

---

# 14. 문제 1: @Autowired로 구현체를 직접 참조하는 코드

예를 들어:

```java
@Autowired
private PaymentServiceImpl paymentService;
```

이런 코드는 모듈 경계를 만들기 어렵다.

```text
Order Module
     │
     ▼
PaymentServiceImpl
     │
Payment Module
```

Module 구현체가 다른 Module에 노출되기 때문이다.

다음처럼 바꿔야 한다.

```java
@Autowired
private PaymentService paymentService;
```

그리고:

```text
PaymentService
```

는 API 모듈에 존재해야 한다.

즉 대규모 기존 애플리케이션에서는 먼저:

```text
구현 클래스 직접 의존
        ↓
API Interface 의존
```

으로 리팩토링해야 한다.

---

# 15. 문제 2: @ComponentScan

일반 Spring 애플리케이션에서는 다음이 흔하다.

```java
@SpringBootApplication
```

사실상:

```java
@ComponentScan("com.company")
```

으로 전체 애플리케이션을 탐색한다.

하지만 모듈 구조에서는 이것이 큰 문제가 된다.

```text
MainContext
    ↓
ComponentScan
    ↓
Module A classes
Module B classes
Module C classes
```

를 Main ClassLoader가 읽어버리면 ClassLoader 격리가 무너진다.

따라서 Module은 각각:

```java
@ComponentScan(
    basePackages =
        "com.company.payment"
)
```

처럼 별도로 scan해야 한다.

더 안전한 방식은 아예:

```java
@Configuration
@Import({
    PaymentServiceImpl.class,
    PaymentRepository.class
})
```

처럼 명시적인 configuration을 두는 것이다.

---

# 16. 문제 3: Spring Boot AutoConfiguration

Spring Boot의 가장 큰 장점이 오히려 여기에서는 복잡성을 만든다.

Boot는 classpath를 보고:

```text
특정 Class 존재?
특정 Bean 존재?
특정 Property 존재?
```

등을 판단하여 자동으로 Bean을 만든다.

예:

```java
@ConditionalOnClass
@ConditionalOnMissingBean
@ConditionalOnProperty
```

모듈마다 ClassLoader가 다르면:

```text
Main Context가 보는 classpath

≠

Module Context가 보는 classpath
```

가 된다.

따라서 다음 문제가 발생할 수 있다.

```text
왜 Module A에서는 AutoConfiguration이 실행되고
Module B에서는 안 되지?
```

Boot auto-configuration metadata와 resource loading도 ClassLoader 경계를 고려해야 한다.

Spring Boot는 기본적으로 하나의 애플리케이션 런타임과 classpath를 전제로 사용하는 것이 훨씬 자연스럽다.

---

# 17. 문제 4: JPA가 특히 어렵다

가장 어려운 영역 중 하나다.

예를 들어 Module마다 Entity가 있다고 하자.

```text
Order Module
 ├─ Order
 └─ OrderItem

Payment Module
 └─ Payment
```

일반 Spring Boot에서는:

```text
EntityManagerFactory
       │
       ├─ Order
       ├─ OrderItem
       └─ Payment
```

를 시작할 때 한꺼번에 구성한다.

그런데 Payment Module을 hot deploy하면서:

```text
새 Entity 추가
```

를 한다고 하자.

기존 `EntityManagerFactory`에 런타임으로 Entity metadata 하나만 자연스럽게 추가하기는 어렵다.

Hibernate의 metamodel은 기본적으로 EntityManagerFactory 생성 시 구성된다.

따라서 선택지는 크게 두 가지다.

### 방법 A

모듈마다 EntityManagerFactory를 둔다.

```text
Order Module
 └─ OrderEntityManagerFactory

Payment Module
 └─ PaymentEntityManagerFactory
```

장점:

```text
독립 lifecycle
```

단점:

```text
Entity 관계 처리 어려움
Cross-module transaction 어려움
Connection Pool 관리 복잡
```

### 방법 B

JPA 영역은 Core에 둔다.

```text
Core
 └─ EntityManagerFactory
      ├─ Order
      └─ Payment
```

이 경우:

```text
JPA Entity 변경
=
Core restart 필요
```

가 된다.

현실적으로는 **모든 기능을 hot deploy 대상으로 만들지 않고 hot deploy 가능한 영역을 제한하는 방식이 훨씬 현실적이다.**

---

# 18. MyBatis 역시 고려해야 한다

MyBatis는 JPA보다는 상대적으로 동적 구성에 유리하지만 문제는 남는다.

```text
SqlSessionFactory
    │
    ├─ Mapper A
    ├─ Mapper B
    └─ Mapper C
```

Module이 없어졌을 때:

```text
Mapper registration
MappedStatement
TypeHandler
ResultMap
```

같은 metadata에 Module Class가 남으면 ClassLoader가 unload되지 않을 수 있다.

따라서 선택지는:

```text
Module마다 SqlSessionFactory
```

또는

```text
Core SqlSessionFactory에
동적 register + unregister 기능 구현
```

이다.

후자는 생각보다 관리가 복잡하다.

---

# 19. 문제 5: @Transactional

다음 코드가 있다고 하자.

```java
@Transactional
public void order() {

    orderRepository.save();

    paymentService.pay();
}
```

기존에는:

```text
같은 ApplicationContext
같은 TransactionManager
```

안에서 실행되기 때문에 자연스럽다.

Module을 분리하더라도 모두 같은 `PlatformTransactionManager`를 공유한다면 동일 스레드에서 트랜잭션을 공유하는 구조를 만들 수는 있다.

하지만 모듈 독립성을 강하게 가져가서:

```text
Order Module
 └─ TransactionManager A

Payment Module
 └─ TransactionManager B
```

로 만들면 더 이상 하나의 로컬 트랜잭션이라고 볼 수 없다.

따라서 설계 초기에:

```text
Transaction boundary
=
Module boundary인가?
```

를 정해야 한다.

이것은 상당히 중요한 아키텍처 결정이다.

---

# 20. 문제 6: Spring AOP Proxy

Spring에서는 다음 기능들이 proxy를 많이 사용한다.

```text
@Transactional
@Async
@Cacheable
@Retryable
Method Security
Custom AOP
```

예를 들어:

```text
Module ClassLoader
      ↓
PaymentServiceImpl
      ↓
CGLIB Proxy
```

가 만들어질 수 있다.

이 proxy가 Core 영역에서 cache되면:

```text
Core
 ↓
Proxy
 ↓
PaymentServiceImpl
 ↓
ModuleClassLoader
```

가 되어 ClassLoader가 unload되지 않는다.

따라서 **Module 구현 클래스 기반 CGLIB proxy를 외부에 노출하는 것은 특히 피해야 한다.**

가능하면:

```text
Core API Interface

       ↓

JDK Dynamic Proxy

       ↓

ServiceRegistry

       ↓

Module implementation
```

구조가 안전하다.

---

# 21. 문제 7: Thread

이것은 hot deploy 구현에서 매우 중요하다.

Module 내부에서:

```java
new Thread(...)
```

또는:

```java
Executors.newFixedThreadPool(...)
```

을 만들었다고 하자.

Module을 unload해도 thread가 계속 살아 있으면:

```text
Thread
  ↓
ContextClassLoader
  ↓
ModuleClassLoader
```

가 유지된다.

그러면 ClassLoader가 GC되지 않는다.

따라서 Module stop 시:

```text
ExecutorService.shutdown()
Scheduler.shutdown()
Thread interrupt
```

가 반드시 이루어져야 한다.

Spring의:

```text
ThreadPoolTaskExecutor
ThreadPoolTaskScheduler
```

처럼 lifecycle 관리가 되는 Bean으로 한정하는 것이 좋다.

---

# 22. 문제 8: ThreadLocal

더 까다로운 것이 ThreadLocal이다.

```java
static ThreadLocal<MyContext> context;
```

Module 객체가 들어간 상태에서 서버의 worker thread가 살아 있으면:

```text
Tomcat Thread
      ↓
ThreadLocal
      ↓
Module Object
      ↓
Module Class
      ↓
ModuleClassLoader
```

가 된다.

이 역시 ClassLoader leak이다.

특히 다음 라이브러리를 주의해야 한다.

```text
Logging MDC
SecurityContext
Custom ThreadLocal
Tracing
ORM
RPC Context
```

---

# 23. 문제 9: static

다음 코드가 있는 경우:

```java
public class Holder {

    public static Object INSTANCE;

}
```

Core 쪽 static field가 Module 객체를 참조하면 끝이다.

```text
CoreClassLoader
      ↓
static
      ↓
Module Object
      ↓
ModuleClassLoader
```

ClassLoader unload가 불가능하다.

따라서 Module architecture에서는 static state 사용을 훨씬 엄격하게 관리해야 한다.

---

# 24. 문제 10: Jackson

예를 들어 Core에:

```java
ObjectMapper
```

가 하나 있고 Module A의 DTO를 serialize했다고 하자.

Jackson 내부에는 serializer/deserializer 관련 cache가 존재한다.

```text
Core ObjectMapper
       ↓
Serializer Cache
       ↓
Module DTO Class
       ↓
ModuleClassLoader
```

구조가 만들어지면 ClassLoader가 살아남을 가능성이 있다.

따라서 Module 전용 타입을 shared infrastructure가 장기간 cache하는 라이브러리는 모두 검토해야 한다.

Jackson뿐 아니라:

```text
Bean Introspection
Reflection cache
Validation metadata
Expression cache
Serialization framework
```

등도 같은 문제를 가진다.

---

# 25. 문제 11: 웹 Controller를 동적으로 교체하기 어렵다

Spring MVC에서:

```java
@RestController
class PaymentController {
}
```

는 기동 시 `RequestMappingHandlerMapping`에 등록된다.

```text
PaymentController
       ↓
RequestMappingHandlerMapping
```

Module을 unload하면 mapping도 제거해야 한다.

그렇지 않으면:

```text
RequestMappingHandlerMapping
      ↓
HandlerMethod
      ↓
PaymentController
      ↓
ModuleClassLoader
```

가 남는다.

따라서 Controller까지 hot deploy 대상으로 만들려면:

```text
Module Start
   ↓
Controller 탐색
   ↓
RequestMapping 동적 등록

Module Stop
   ↓
RequestMapping unregister
```

기능까지 구현해야 한다.

이것은 Service Bean만 동적으로 교체하는 것보다 훨씬 어렵다.

따라서 초기 구현에서는:

```text
Controller
        ↓
Core에 유지

Service implementation
        ↓
Hot Deploy Module
```

구조가 더 현실적이다.

---

# 26. 가장 현실적인 경계

모든 것을 Module로 만드는 것보다는 다음 구조가 현실적이다.

```text
Core Runtime
│
├─ Controller
├─ Security
├─ DataSource
├─ Transaction
├─ JPA Infrastructure
├─ HTTP Server
├─ Logging
├─ Monitoring
├─ Module Manager
└─ Service Registry
        │
        ▼
Dynamic Modules
│
├─ Business Rule
├─ Converter
├─ Analyzer
├─ Processor
├─ Validation Logic
├─ Migration Logic
└─ Extension Logic
```

즉:

> **인프라는 고정하고 비즈니스 실행 로직만 동적으로 교체한다.**

이 방식이 가장 구현 난이도 대비 효과가 좋다.

---

# 27. 기존 Spring 애플리케이션을 Spring DM식 구조로 바꿀 때 발생하는 변화

코드 규모를 단순히:

```text
기존 코드 100
```

이라고 하면 단순 패키지 분리 정도로 끝나지 않는다.

다음 작업들이 필요하다.

```text
1. Module boundary 정의
2. API/Implementation 분리
3. 구현체 직접 의존 제거
4. ComponentScan 분리
5. Circular dependency 제거
6. static reference 제거
7. ClassLoader-safe API 설계
8. Module lifecycle 정의
9. Service Registry 구현
10. Dynamic proxy 구현
11. Resource cleanup 구현
12. Thread cleanup
13. Cache cleanup
14. Module dependency graph
15. Module version 관리
16. 운영/모니터링 추가
```

사실상 **애플리케이션 architecture를 변경하는 작업**이다.

---

# 28. 가장 문제가 되는 것은 순환 의존성

기존 대규모 애플리케이션에서는 이런 구조가 흔하다.

```text
Module A
   ↓
Module B
   ↓
Module C
   ↓
Module A
```

하나의 Spring Context에서는 이것이 숨어 있을 수 있다.

하지만 ClassLoader boundary를 만들면 문제가 바로 드러난다.

이상적인 구조는:

```text
A → B → C
```

처럼 DAG가 되어야 한다.

또는 서비스 기반으로:

```text
A → Service API ← B
```

로 decouple해야 한다.

결국 OSGi가 강제로 요구했던 좋은 모듈 설계를 직접 해야 한다.

---

# 29. Module dependency도 관리해야 한다

예를 들어:

```text
order-module
requires
payment-api >= 2.0
```

같은 정보가 필요하다.

모듈 descriptor를 직접 정의할 수 있다.

예:

```yaml
id: order
version: 1.3.0

requires:
  - module: payment-api
    version: ">=2.0.0"

exports:
  - com.company.order.api.OrderService

imports:
  - com.company.payment.api.PaymentService
```

즉 Spring DM의:

```text
MANIFEST.MF
Import-Package
Export-Package
```

를 보다 단순한 자체 metadata로 대체하는 것이다.

---

# 30. Java 21의 JPMS를 사용하면 해결되지 않는가?

Java에는 이미:

```text
module-info.java
```

를 사용하는 JPMS가 있다.

예:

```java
module payment.module {

    requires payment.api;

    exports com.company.payment.api;
}
```

하지만 JPMS의 목적은 OSGi와 조금 다르다.

JPMS는 강력한 **정적 모듈 경계**를 제공하지만:

```text
install
uninstall
update
service tracking
dynamic lifecycle
```

을 OSGi처럼 application server 수준에서 제공하지 않는다.

따라서:

```text
JPMS
+
Custom ModuleLayer
+
ClassLoader
+
Spring Context
```

를 조합할 수는 있지만 구현 난이도가 크게 높아진다.

첫 구현에서는 JPMS까지 넣지 않는 편이 좋다.

---

# 31. Spring Modulith는 대안인가?

Spring Modulith는 현재 Spring 생태계에서 애플리케이션 모듈 구조를 정의하고 검증하는 데 유용하다. 최신 문서에서도 Application Module 구조를 런타임에서 접근하거나 검증하고, 모듈별 초기화 기능 등을 제공한다.

하지만 목적은 다르다.

```text
Spring Modulith

하나의 JVM
하나의 ClassLoader
주로 하나의 ApplicationContext

      ↓

논리적 Module
```

에 가깝다.

반면 여기서 원하는 것은:

```text
Module A
ClassLoader A
Context A

Module B
ClassLoader B
Context B
```

이다.

따라서 Spring Modulith는:

```text
Module 경계 탐색
Dependency 검증
Architecture 정리
```

에는 매우 좋지만,

```text
Runtime install
Runtime unload
ClassLoader 교체
Hot deployment
```

를 대신해주는 기술은 아니다.

오히려 기존 애플리케이션을 먼저 Spring Modulith식으로 정리한 뒤, 일부 모듈을 실제 dynamic module로 분리하는 순서가 괜찮다.

---

# 32. Spring Boot DevTools와도 다르다

Spring Boot DevTools도 ClassLoader를 사용해 빠른 restart를 구현한다.

Spring Boot 공식 문서에 따르면 변경되지 않는 라이브러리는 `base ClassLoader`, 개발 중인 코드는 `restart ClassLoader`에 두고 restart할 때 restart ClassLoader를 버리고 새로 만드는 방식을 사용한다.

즉:

```text
Base ClassLoader
       │
       ▼
Restart ClassLoader
```

구조다.

이 점은 매우 참고할 만하다.

하지만 DevTools는:

```text
Module A만 restart
Module B 유지
```

를 위한 것이 아니다.

대체로:

```text
ApplicationContext 전체 종료
       ↓
Restart ClassLoader 교체
       ↓
ApplicationContext 전체 재생성
```

이다.

따라서 **ClassLoader 교체 구현은 참고할 수 있지만 모듈 hot deploy 솔루션은 아니다.**

---

# 33. 실제 구현한다면 권장 아키텍처

내가 이런 시스템을 Spring Boot 3.x / Java 21에서 처음 만든다면 다음 구조로 시작하는 것이 가장 현실적이다.

```text
                    JVM
                     │
       ┌─────────────┴─────────────┐
       │                           │
 Core Runtime                Dynamic Modules
       │                           │
 Main Context               Module Context
       │                           │
 ├─ Web                     ├─ Business Logic
 ├─ Security                ├─ Processor
 ├─ DB                      ├─ Converter
 ├─ Transaction             ├─ Rule
 ├─ ModuleManager           └─ Extension
 ├─ ServiceRegistry
 └─ Monitoring
```

그리고 **hot deploy 가능 범위를 의도적으로 좁게 잡는다.**

---

# 34. 1단계

기존 애플리케이션을 logical module로 정리한다.

```text
Application

├─ conversion
├─ rewrite
├─ source-generation
├─ compile-check
└─ analysis
```

이 단계에서는 ClassLoader를 나누지 않는다.

Spring Modulith 등을 사용해 module dependency를 검증하는 것도 좋은 방법이다.

---

# 35. 2단계

Module API를 분리한다.

```text
rewrite-api

interface RewriteService
DTO
Exception
Event
```

그리고:

```text
rewrite-impl
```

을 만든다.

중요한 규칙은:

```text
다른 모듈
     │
     X
rewrite-impl

다른 모듈
     │
     O
rewrite-api
```

이다.

---

# 36. 3단계

Module별 ApplicationContext를 만든다.

아직 별도 ClassLoader까지 적용하지 않아도 된다.

```text
Main Context
    │
    ├─ Rewrite Context
    ├─ Convert Context
    └─ SourceGen Context
```

이 단계에서 Bean lifecycle 문제를 먼저 해결한다.

---

# 37. 4단계

Service Registry와 Proxy를 만든다.

```text
Consumer
    │
    ▼
Interface Proxy
    │
    ▼
ServiceRegistry
    │
    ▼
Module Service
```

모듈 간 직접 Spring Bean reference를 제거한다.

이 단계가 성공하면 실제 동적 교체가 훨씬 쉬워진다.

---

# 38. 5단계

Module별 ClassLoader를 적용한다.

```text
Main ClassLoader
      │
 ┌────┴────┐
 ▼         ▼
CL A      CL B
```

이제 진짜 runtime isolation이 생긴다.

그리고:

```text
stop
close context
close classloader
GC
```

가 제대로 되는지 검증한다.

---

# 39. 반드시 ClassLoader leak 테스트를 만들어야 한다

Hot deployment 플랫폼에서 기능 테스트만 해서는 부족하다.

예를 들어 Module을:

```text
install
update
uninstall
```

100회 반복한다.

```java
for (int i = 0; i < 100; i++) {

    moduleManager.install(...);

    moduleManager.start(...);

    moduleManager.stop(...);

    moduleManager.uninstall(...);
}
```

그리고:

```text
Heap
Metaspace
Thread count
ClassLoader count
Loaded Class count
```

를 관찰해야 한다.

정상이라면:

```text
CL v1 ── GC
CL v2 ── GC
CL v3 ── GC
```

가 되어야 한다.

문제가 있으면:

```text
CL v1
CL v2
CL v3
CL v4
CL v5
...
```

가 계속 남는다.

이 테스트가 사실상 hot deploy 구현의 핵심 테스트다.

---

# 40. 그래서 기존 Spring 애플리케이션을 실제 Spring DM으로 옮기는 것은 어떨까?

기술적으로 아이디어 자체는 맞지만 **Spring Boot 3.x 기반 기존 시스템을 실제 Spring DM으로 마이그레이션하는 것은 추천하지 않는다.**

Spring DM의 안정 문서는 1.2.1 시대에 머물러 있고, 2.0 snapshot도 Spring 3.x와 OSGi Blueprint 초기 세대를 대상으로 한다. 반면 Spring Boot 3.5는 Spring Framework 6.2 계열이며 Java 17 이상을 요구하고 Java 21을 정상 지원한다. Spring Framework 6부터는 `javax.*` 중심의 옛 Java EE API에서 `jakarta.*` 기반으로 이동했다.

따라서 실제로는:

```text
Spring Boot 3
Spring Framework 6
Jakarta
Hibernate 6
Tomcat 10
Java 21

        ↕

Spring DM
Spring 2.x / 3.x 시대
과거 OSGi ecosystem
```

의 세대 차이가 너무 크다.

문제 하나를 해결하려고 기존 현대 Spring 애플리케이션 전체를 과거 ecosystem으로 끌고 가는 결과가 될 가능성이 높다.

---

# 41. 더 중요한 것은 "Spring DM을 쓸 것인가"가 아니다

본질적인 질문은 다음이다.

> 기존 대규모 Spring ApplicationContext에서 어떤 기능까지 독립적인 lifecycle을 가져야 하는가?

예를 들어 전체가:

```text
500,000 Beans
```

이라고 하더라도 실제 빈번하게 변경되는 부분이:

```text
Rewrite       20,000
Converter     10,000
Rules          5,000
Extensions     2,000
```

이라면 굳이 전체를 동적 모듈 구조로 바꿀 필요가 없다.

오히려:

```text
Stable Core
────────────────────────

Controller
Security
DB
Transaction
Framework
Infrastructure

────────────────────────

Dynamic Extension Layer
────────────────────────

Rewrite
Conversion
Rule
Extension
Plugin
```

처럼 나누는 것이 좋다.

---

# 42. 내가 가장 추천하는 형태

Spring Boot 3.x / Java 21에서는 다음 정도의 아키텍처가 현실적으로 가장 균형이 좋다.

```text
┌───────────────────────────────────────────┐
│               Main JVM                    │
│                                           │
│ Spring Boot                               │
│                                           │
│ ┌─────────────────────────────────────┐   │
│ │ Core ApplicationContext             │   │
│ │                                     │   │
│ │ Web / Security / DB / Transaction   │   │
│ │ ModuleManager                       │   │
│ │ ServiceRegistry                     │   │
│ │ ProxyFactory                        │   │
│ └─────────────────────────────────────┘   │
│              │                            │
│      Stable API Interfaces                │
│              │                            │
│   ┌──────────┼──────────┐                 │
│   ▼          ▼          ▼                 │
│ Module A   Module B   Module C             │
│   │          │          │                 │
│ CL A       CL B       CL C                 │
│   │          │          │                 │
│ Context A  Context B  Context C            │
│                                           │
└───────────────────────────────────────────┘
```

그리고 가장 중요한 원칙을 몇 가지 두는 것이다.

```text
Module API는 Core ClassLoader가 로딩한다.

Module 구현체는 Module ClassLoader 밖으로
직접 노출하지 않는다.

모듈 간 호출은 API Interface + Proxy로 한다.

인프라는 최대한 Core에 둔다.

JPA Entity는 초기에는 hot deploy 대상에서 제외한다.

Controller도 초기에는 Core에 둔다.

Module 내부 Thread는 반드시 lifecycle 관리한다.

Core cache가 Module Class를 저장하지 못하게 한다.

Module stop 시 모든 resource를 명시적으로 정리한다.
```

---

# 43. Spring DM과 비교하면

최종적으로 이런 대응 관계가 된다.

| Spring DM / OSGi | 현대식 자체 Runtime |
|---|---|
| Bundle | Module JAR |
| BundleClassLoader | ModuleClassLoader |
| BundleContext | ModuleContext |
| Spring DM ApplicationContext | Module ApplicationContext |
| OSGi Service Registry | Custom ServiceRegistry |
| OSGi Service Reference | Dynamic Proxy |
| Bundle Activator | ModuleLifecycle |
| MANIFEST Import-Package | Module Descriptor |
| MANIFEST Export-Package | API Module |
| Bundle update | ModuleManager.update() |
| Virgo Kernel | Core Runtime |
| Virgo Repository | Module Repository |
| OSGi Resolver | DependencyResolver |

결국 **Spring DM의 핵심 개념만 현대 Spring 환경에 맞게 다시 구현하는 것**이다.

---

# 44. 이 구조의 가장 큰 위험

이 구조가 기술적으로 흥미롭다고 해서 반드시 좋은 선택인 것은 아니다.

결국 작은 application server 하나를 직접 만드는 것과 비슷해진다.

처음에는:

```text
ClassLoader
+
ApplicationContext
```

만 만들면 될 것처럼 보이지만 시간이 지나면:

```text
Module lifecycle
Dependency resolver
Version policy
Service registry
Proxy
Health check
Thread cleanup
Resource cleanup
Rollback
Deployment locking
Traffic draining
Monitoring
ClassLoader leak detection
Module dependency graph
Failure recovery
```

가 필요해진다.

Virgo가 복잡했던 이유도 바로 이것이다.

---

# 45. 따라서 가장 현실적인 전략

전체 Spring 애플리케이션을 한 번에 OSGi화하지 않는 것이 중요하다.

추천 순서는:

```text
현재 Monolith
      ↓
논리적 Module 분리
      ↓
API / Implementation 분리
      ↓
Module 간 직접 Bean 참조 제거
      ↓
Service Interface 기반 연결
      ↓
Module별 ApplicationContext
      ↓
동적 lifecycle
      ↓
ClassLoader isolation
      ↓
선택적인 Hot Deployment
```

이다.

특히 처음부터:

```text
OSGi
ClassLoader
Hot Deployment
```

를 넣기보다는 먼저 **ApplicationContext 분리까지 했을 때 기존 시스템이 정상적으로 동작하는지를 보는 것이 좋다.**

그다음 ClassLoader를 분리하면 문제 발생 지점을 훨씬 명확하게 찾을 수 있다.

---

# 결론

Spring DM이 풀려고 했던 문제는 지금 봐도 상당히 타당하다.

```text
거대한 Spring Application
        ↓
작은 Runtime Module
        ↓
독립 lifecycle
        ↓
부분 교체
```

그러나 Spring Boot 3.x / Java 21에서 실제 Spring DM을 사용하는 것은 기술 세대 차이 때문에 현실적인 선택이 아니다. Spring Boot 3.x는 Spring Framework 6과 Jakarta 기반의 현대 ecosystem에 속하는 반면, Spring DM은 Spring 2.x~3.x 시대의 OSGi 통합 기술이다.

대신 Spring DM/Virgo에서 검증된 핵심 아이디어인:

```text
ClassLoader isolation
ApplicationContext isolation
Stable API
Service Registry
Dynamic Proxy
Module Lifecycle
```

만 가져오는 것이 더 적합하다.

그리고 기존 Spring 애플리케이션을 이런 구조로 바꿀 때 가장 큰 문제는 **Spring 자체가 아니라 기존 코드에 숨어 있는 모듈 간 결합**이다.

특히:

```text
@ComponentScan
@Autowired 구현체 의존
JPA Entity
MyBatis metadata
AOP Proxy
Thread / ThreadLocal
static state
shared cache
Controller mapping
circular dependency
```

가 실제 난이도를 결정한다.

따라서 대규모 기존 시스템이라면 **“전체 Spring 애플리케이션을 hot deploy 가능하게 만든다”보다 “변경 빈도가 높고 독립성이 높은 특정 실행 영역만 plugin/module화한다”는 목표로 시작하는 것이 가장 현실적이다.**

Spring DM과 Virgo를 참고한다면 기술 그 자체보다도 **왜 API와 구현을 분리했는지, 왜 Service Registry가 필요한지, 왜 ClassLoader lifecycle을 엄격하게 관리했는지​**를 가져오는 것이 가장 가치가 크다.