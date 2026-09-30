# hot-reload

Spring Boot 3.5.16 / Java 17 기반 개발용 하이브리드 재로딩 엔진.

**표준 starter는 수정 없이 사용하고, 명시한 업무 코드만 빠르게 교체한다. 유지 인프라에 영향을 주는
변경은 서버와 JVM을 포함한 전체 재시작으로 반영한다.**

현재 계약은 [하이브리드 재로딩 스펙](docs/hybrid-reload-spec.md)을 기준으로 한다.
`docs/hot-reload-project-spec.md`는 이전 프로젝트 생성 지시서다.

## 실행 구조

- `reload/`: 엔진 라이브러리, 변경 분류, 세대 관리, 전체 재시작 감독자.
- `sample-apps/greeting/`: 표준 web starter를 사용하는 예제.
- `business-packages`를 지정하면 서버와 공용 인프라는 부모, 업무 코드와 MVC 실행 인프라는 자식 세대에 둔다.
- 지정하지 않으면 일반 Spring Boot 자동 구성/MVC를 유지하고 전체 재시작만 사용한다.
- `reload.enabled=false`이면 엔진을 완전히 끈다.

업무 영역 밖의 클래스, `parent-packages` 안의 클래스, `@SpringBootApplication`과 그 중첩 클래스는
부모 소유다. 공통 인터페이스·영속성 엔티티·매퍼·서버 설정은 부모에 두고 웹·업무 서비스부터 재로딩한다.
부모가 자식 타입을 직접 참조하거나 자식 객체를 공용 캐시에 오래 보관하면 안 된다.

## 빠른 시작

전체 재시작까지 자동화하려면 감독자 아래에서 실행한다.

```bash
# 터미널 1: 전체 재시작 때도 Gradle이 의존성과 빌드 설정을 다시 해석한다.
./gradlew :sample-apps:greeting:hotReloadRun -PappArgs='--reload.trigger.mode=api'

# 터미널 2: 컴파일이 끝난 후 변경을 한 번 분류하고 적용한다.
./gradlew :sample-apps:greeting:triggerReload --continuous
```

`web/HelloController.java`를 바꾸면 부모 자원과 서버는 그대로이고 세대만 교체된다.
`shared/DefaultGreetingService.java`를 바꾸면 애플리케이션 JVM을 종료하고 새 프로세스로 시작한다.

watch 모드는 다음과 같다.

```bash
./gradlew :sample-apps:greeting:hotReloadRun
./gradlew :sample-apps:greeting:classes --continuous
```

파일 감시의 quiet-period는 빌드 완료 신호가 아니다. 대규모 증분 빌드는 API 방식을 권장한다.

`bootRun`만 실행해도 업무 재로딩은 가능하지만 감독자가 없으면 인프라 변경에 대해 409/restart-required를
돌려주고 기존 서비스를 유지한다. 애플리케이션 `main` 코드는 변경하지 않는다.

## 소비자 설정

```yaml
reload:
  business-packages:
    - com.example.app.web
    - com.example.app.business
  parent-packages:
    - com.example.app.business.shared
  # 생략하면 애플리케이션 클래스의 출력 디렉터리를 사용한다.
  # 다른 업무 모듈도 재로딩하려면 출력 디렉터리를 모두 지정한다.
  # classpath: [build/classes/java/main, ../business/build/classes/java/main]
  # base-packages: [com.example.app]
  watch-paths:
    - build/resources/main
    - build.gradle
    - settings.gradle
    - gradle/libs.versions.toml
  trigger:
    mode: api                 # watch | api | both
    api-path: /_reload
  poll-interval: 1s
  quiet-period: 400ms
  drain-timeout: 30s
  exclude-patterns: ["**/*.log"]
```

상대 경로는 애플리케이션 프로세스의 작업 디렉터리 기준이다. 실행 클래스패스의 디렉터리와 JAR는
자동 검사하며, 그 밖의 빌드 파일·외부 설정·ProObject 서비스 XML은 watch-paths에 지정한다.
JAR는 크기/수정 시각으로, 클래스·리소스는 내용 해시로 비교한다. business-packages의 빈 목록은
일반 Boot + 전체 재시작 모드다.

감독자는 JDK만으로 실행할 수 있다(엔진 JAR 경로는 빌드 결과에 맞게 지정).

```bash
java -cp reload/build/libs/reload-0.0.1-SNAPSHOT.jar \
  com.example.reload.restart.RestartLauncher -- ./gradlew :sample-apps:greeting:bootRun --no-daemon
```

명령과 인자는 셸 문자열로 합치지 않고 그대로 전달한다. Maven 명령도 사용할 수 있다.
고정 `java -cp` 명령을 감독하면 기존 클래스패스가 유지되므로 의존성 추가/삭제까지 반영하려면
클래스패스를 다시 계산하는 빌드 명령을 감독해야 한다. 전체 재시작 후 기동 실패는 무한 재시도하지 않는다.

## 변경 분류와 HTTP API

| 변경 | 처리 |
|---|---|
| 업무 클래스/DTO 추가·수정·삭제 | 새 자식 세대 생성 및 교체 |
| 부모 클래스 또는 업무·부모 혼합 변경 | 전체 재시작 |
| 설정 클래스, 엔티티·매퍼, 예약/메시지 설정, 리소스, JAR, 빌드 파일 | 전체 재시작 |
| 클래스 내용을 알 수 없는 변경 | 전체 재시작 분류 |
| 동일한 바이트의 재컴파일 | 감시에서는 무시 |

`POST /_reload`는 감시와 같은 baseline/분류기를 사용한다.

| HTTP | action | 의미 |
|---|---|---|
| 200 | reload | 새 업무 세대 적용 완료 |
| 202 | restart-requested | 전체 재시작 예약. 완료 여부는 새 프로세스 readiness로 확인 |
| 409 | restart-required | 감독자가 없어 재시작 불가. 기존 세대 유지 |
| 500 | failed | 세대 생성/검증 실패. 기존 세대와 baseline 유지 |

응답에는 `reloaded`, `generation`, `previousGeneration`, `durationMillis`, `failedReloads`,
`action`, `reasons`, `error`가 있다. `GET /_reload`는 트리거 mode, strategy,
restartAvailable, 현재 세대와 실패 횟수를 돌려준다. API에는 인증이 없으므로 개발 환경에서만 노출한다.

## starter와 ProObject 연동

부모에 전체 자동 구성을 유지한다. hybrid에서는 부모 MVC 자동 구성만 제외하고 세대별 MVC가 요청을
처리한다. 기본 자식 인프라는 AOP·트랜잭션·캐시·메서드 검증을 제공한다. 부모 ObjectMapper의 설정을
복사하고 `WebMvcRegistrations`가 제공하는 mapping/adapter/exception resolver를 세대마다 생성한다.

부모 `BeanPostProcessor`와 `Advisor`가 자식 빈에 자동 적용되는 것은 아니다. 추가 연동은 부모의
`GenerationIntegration` 빈에서 자식 설정을 등록하고 공개 전에 검증한다. 통합 객체가 자식 참조를
보관해서는 안 된다. 비 HTTP 진입점은 `GenerationManager.withGeneration`으로 세대를 고정할 수 있다.

ProObject 22가 있으면 HTTP 전용 어댑터와 세대별 실행 빈 연동을 자동 적용한다. ObjectFactory,
클래스로더 holder, dispatcher, 서비스 핸들러 등은 세대에 다시 만들고 ApplicationManager/메타/서버는
유지한다. 부모 dispatcher는 현재 요청에 고정한 세대로 전달한다. 서비스 XML 변경은 전체 재시작이다.
실제 22.0.0 아티팩트의 MVC 요청 컨텍스트와 객체 팩터리 계약 테스트가 별도로 있다.

표준 starter를 수정하지 않는다는 의미가 모든 starter 기능의 부분 재로딩을 보장한다는 의미는 아니다.
JPA/매퍼는 유지 영역에 두고, 자식의 스케줄·메시지 소비·임의 비동기 실행·세대 객체를 저장한 세션/캐시,
서블릿 등록 변경에는 별도 계약이 필요하다. ProObject의 JEUS 전체 기동·원격 연동·비동기 이미지로그는
소비자 애플리케이션에서 추가 검증해야 한다. 지원 범위 밖의 구성에는 전체 재시작 모드를 사용한다.
기존 제약 시나리오는 [분석 문서](docs/reload-aop-proxy-risks.md)에 남아 있다.

## 검증

```bash
./gradlew build
# 로컬 Maven 저장소에 ProObject 22 아티팩트가 설치되어 있는 환경:
./gradlew -PproobjectCompatibility :reload:test --tests '*ProObjectIntegrationTest'
# 기존에 알려진 미지원 시나리오(일부 실패가 예상됨):
./gradlew :reload:knownIssueTest
```

기본 테스트에는 변경 분류, API 혼합 변경 차단, 부모 자원 유지, 실제 별도 JVM의 전체 재시작,
실패 시 기존 세대 유지, 삭제 타입 fallback 방지, 중첩 호출 세대 고정, 반복 교체 후 클래스로더 회수가 포함된다.
ProObject 선택 테스트는 별도로 실행한다. 이 옵션을 켜면 테스트 클래스패스에 ProObject 자동 구성이 추가되기 때문이다.

Spring Boot 3.5.16, Gradle wrapper 9.5.1, Java 17 toolchain을 사용한다. Windows에서 사용자 경로에
한글이 있고 Gradle worker가 시작되지 않으면 ASCII 경로의 GRADLE_USER_HOME을 지정한다.
