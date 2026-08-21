# Spring Boot hot deploy prototype

`runtime`은 Spring Boot root context를 한 번만 기동하고, `app` JAR마다 별도
classloader와 child `GenericApplicationContext`를 만들어 교체한다. `app`과
`runtime-api`에는 Spring 의존성이 없다.

## Build and test

```bash
./gradlew clean test
```

## Run

먼저 app JAR를 runtime이 허용하는 repository 아래에 둔다.

```bash
./gradlew :app:jar
mkdir -p deployments/repository/sample-app/1.0.0
cp app/build/libs/app-0.0.1-SNAPSHOT.jar \
  deployments/repository/sample-app/1.0.0/app.jar

RUNTIME_ADMIN_PASSWORD='replace-with-a-secret' ./gradlew :runtime:bootRun
```

Business API는 기본적으로 `8080`, 인증된 management API는 loopback
`8081`에서 열린다.

```bash
curl -u runtime-admin:replace-with-a-secret \
  -H 'Content-Type: application/json' \
  -d '{
    "action": "deploy",
    "artifactPath": "deployments/repository/sample-app/1.0.0/app.jar"
  }' \
  http://127.0.0.1:8081/actuator/hotdeploy

curl -H 'Content-Type: application/json' \
  -d '{"operation":"hello","attributes":{}}' \
  http://127.0.0.1:8080/api/business
```

현재 상태 조회와 retention 내 rollback:

```bash
curl -u runtime-admin:replace-with-a-secret \
  http://127.0.0.1:8081/actuator/hotdeploy

curl -u runtime-admin:replace-with-a-secret \
  -H 'Content-Type: application/json' \
  -d '{"action":"rollback","deploymentId":"<sha256>"}' \
  http://127.0.0.1:8081/actuator/hotdeploy
```

Runtime bean 설정만 refresh한다. raw `/actuator/refresh`는 노출하지 않는다.

```bash
curl -u runtime-admin:replace-with-a-secret \
  -H 'Content-Type: application/json' \
  -d '{"runtime.message.prefix":"refreshed:"}' \
  http://127.0.0.1:8081/actuator/runtimeRefresh
```

refresh API는 management child context에만 등록된다. 허용되지 않은 key가
하나라도 섞이면 전체 refresh가 거절된다. hot-deployed
JAR는 보안 sandbox가 아니므로 신뢰된 내부 artifact만 repository에 넣어야 한다.

상세한 경계, 상태 전환, drain/rollback 정책은
[`docs/hot-deploy-architecture.md`](docs/hot-deploy-architecture.md)를 참고한다.

hot deploy가 성립하는 메커니즘과 실무 적용 방법(인프라 고정 / 서비스 로직만 교체,
`RuntimePort` 확장, 서드파티 라이브러리 배치, Spring annotation 개방 여부)은
[`docs/hot-deploy-adoption-guide.md`](docs/hot-deploy-adoption-guide.md)를 참고한다.
