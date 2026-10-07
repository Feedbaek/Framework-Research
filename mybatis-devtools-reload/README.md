# mybatis-devtools-reload

Spring Boot DevTools와 함께 쓰는 MyBatis mapper XML 런타임 리로드 스타터입니다.

- **Java 변경** → DevTools restart (기존 그대로)
- **mapper XML 변경** → 재시작 없이 해당 namespace만 교체

## 모듈

| 모듈 | 내용 |
|---|---|
| `mybatis-devtools-reload` | 라이브러리 (auto-configuration) |
| `sample-app` | 동작 확인용 예제 (H2, `/users`) |

기준 버전: Spring Boot 3.5.16, mybatis-spring-boot-starter 3.0.5, Java 21

Java 21 바이트코드로 빌드하므로, 이 스타터를 쓰는 애플리케이션도 Java 21 이상에서 실행해야 합니다.

## 사용법

```groovy
dependencies {
    implementation 'io.devreload:mybatis-devtools-reload:0.1.0-SNAPSHOT'
    developmentOnly 'org.springframework.boot:spring-boot-devtools'
}
```

```yaml
mybatis-reload:
  enabled: true            # 기본 false. 로컬 개발 프로필에서만 켤 것
```

| 속성 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 기능 활성화 |
| `watch-dirs` | `[src/main/resources]` | 감시할 **소스** 디렉터리. 상대 경로는 작업 디렉터리 기준 |
| `debounce` | `300ms` | 마지막 파일 이벤트 후 대기 시간 |
| `sync-on-start` | `true` | 시작(재시작 포함) 시 소스와 classpath 사본이 다르면 소스로 다시 적용 |
| `devtools-integration` | `true` | 아래 패턴을 `spring.devtools.restart.additional-exclude`에 추가 |
| `devtools-restart-exclude` | `[mapper/**, mappers/**, **/*Mapper.xml]` | DevTools 재시작을 일으키지 않을 classpath 상대 패턴 |

예제 실행:

```bash
gradle wrapper          # 최초 1회 (wrapper 미포함)
./gradlew :sample-app:bootRun
curl localhost:8080/users
# sample-app/src/main/resources/mapper/UserMapper.xml 의 ORDER BY 를 바꾸고 저장
curl localhost:8080/users   # 재시작 없이 새 SQL 반영
```

## 동작 방식

### DevTools와의 역할 분담

1. `MapperReloadDevToolsEnvironmentPostProcessor`
   - mapper 패턴을 DevTools restart exclude에 추가합니다.
   - IDE 빌드로 XML이 build output에 복사돼도 재시작이 일어나지 않습니다.
   - 사용자가 이미 설정한 값은 유지하고 뒤에 이어 붙입니다.
2. `MapperReloadService`
   - 애플리케이션 컨텍스트의 bean(`SmartLifecycle`)입니다.
   - DevTools 재시작 때마다 `SqlSessionFactory`와 함께 종료되고 다시 생성됩니다.
   - watcher 스레드도 컨텍스트 종료 시 정리되므로, 이전 restart classloader를 붙잡지 않습니다.
3. **소스 디렉터리 감시**
   - build output이 아니라 `src/main/resources`를 감시하므로 저장만 하면 반영됩니다.
   - 소스만 수정한 상태에서 Java 변경으로 재시작이 일어나면 MyBatis는 build output의 오래된 XML을 파싱합니다.
   - 이 경우 `sync-on-start`가 소스 버전으로 다시 맞춥니다.

### 리로드 (`MapperReloader`)

1. 해당 namespace에서 **XML이 정의한 항목만** 제거합니다.
   - 대상: statement, resultMap, parameterMap, `<selectKey>` key generator, `<sql>` fragment
   - 새 XML에 `<cache>`가 있으면 기존 cache도 제거합니다.
2. `XMLMapperBuilder.parse()`로 다시 등록합니다.
   - 이때 TCCL을 애플리케이션 classloader로 설정합니다.
   - DevTools 사용 시 `resultType`이 restart classloader 기준으로 해석되어, `ClassCastException` 문제가 생기지 않습니다.
3. 실패 시 원래 상태로 복원합니다.
   - XML 문법 오류, 존재하지 않는 `resultMap`이나 `include` 참조 등으로 파싱이 실패하면, 등록된 부분을 지우고 원래 항목을 복원합니다.
   - 로그에 에러를 남기고 이전 SQL로 계속 동작합니다.

`@Select` 같은 애노테이션 statement와 MyBatis-Plus가 자동 주입한 CRUD statement는 유지됩니다. MyBatis가 이들의 resource를 `... .java (best guess)`로 기록하기 때문에 XML statement와 구분할 수 있습니다.

`StrictMap`의 short-name 키(`findAll`처럼 namespace 없는 키)도 값의 identity로 함께 정리합니다.

### SqlSessionFactory가 여러 개일 때

- 변경된 XML의 namespace를 이미 가지고 있는 `Configuration`에만 적용합니다.
- 어느 factory에도 없는 namespace(새 mapper 파일)는 다음과 같이 처리합니다.
  - factory가 하나면 거기에 등록합니다.
  - 여러 개면 대상을 정할 수 없으므로 건너뜁니다.

## 제약

- **개발 전용입니다.**
  - registry가 `HashMap`이라서, 리로드하는 순간 다른 스레드의 쿼리가 statement를 잠깐 못 찾을 수 있습니다.
  - 리로드 자체는 `Configuration` 단위로 직렬화됩니다.
- **mapper 인터페이스에 메서드를 추가하면** Java 변경이므로 DevTools 재시작으로 처리됩니다.
- **namespace 간 참조**: 다른 XML이 `<include refid="other.ns.x">`로 참조하는 fragment를 바꾸면, 참조하는 쪽 XML도 저장(리로드)해야 반영됩니다.
  - statement는 파싱 시점에 include를 펼쳐서 보관하기 때문입니다.
- **MyBatis 내부 필드를 리플렉션으로 다룹니다.**
  - 대상 필드: `mappedStatements`, `resultMaps`, `parameterMaps`, `keyGenerators`, `sqlFragments`, `caches`, `loadedResources`
  - MyBatis를 업그레이드하면 테스트로 확인하세요.
- **MyBatis-Plus**
  - 필드 조회를 하위 클래스부터 하므로 `MybatisConfiguration`은 처리됩니다.
  - 자동 주입 CRUD 유지도 설계상 동작해야 하지만 아직 검증하지 않았습니다.
  - 참고로 MyBatis-Plus 3.1.1 이상은 DevTools 사용 시 `TableInfo` 캐시 문제가 알려져 있습니다(공식 FAQ).
- **Gradle 리소스 필터링**(`processResources`의 `expand` 등)을 쓰면 소스와 classpath 사본이 항상 달라집니다.
  - 그래서 `sync-on-start`가 매번 동작합니다. 이 경우 `sync-on-start: false`로 끄세요.
