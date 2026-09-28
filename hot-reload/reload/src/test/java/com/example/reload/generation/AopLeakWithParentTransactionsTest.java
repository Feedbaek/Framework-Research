package com.example.reload.generation;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.reload.fixture.CountingAspect;
import com.example.reload.fixture.NoOpTransactionManager;

/**
 * 부모: 공용 aspect + {@code TransactionManager}(Boot가 부모에 트랜잭션 advisor를 등록한다).
 * <p>
 * 엔진이 자식에 트랜잭션 인프라를 제공하고 부모 advisor는 자식에 쓰지 않으므로, 부모의 트랜잭션 속성
 * 캐시에 자식 클래스가 쌓이지 않아야 한다.
 */
@SpringBootTest(classes = AopLeakWithParentTransactionsTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class AopLeakWithParentTransactionsTest extends AbstractAopLeakTest {

	private static final Path WORK_DIRECTORY = createWorkDirectory("aop-parent-tx");

	private static final ControllerCompiler compiler = new ControllerCompiler(WORK_DIRECTORY,
			WORK_DIRECTORY.resolve("classes"));

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		deploy(compiler, "initial", NONE, NONE);
		registerReloadProperties(registry, WORK_DIRECTORY.resolve("classes"));
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	@Test
	void childWithoutTransactional() throws InterruptedException {
		assertPreviousLoadersCollected("no-transactional", NONE, NONE, false);
	}

	/**
	 * 애플리케이션은 자식에 아무 설정도 하지 않는다. 엔진이 켠 자식 트랜잭션 인프라가 부모
	 * {@code TransactionManager}로 트랜잭션을 연다.
	 */
	@Test
	void childTransactionalUsesEngineProvidedInfrastructure() throws InterruptedException {
		assertPreviousLoadersCollected("engine-tx", NONE, TRANSACTIONAL, true);
	}

	/**
	 * 애플리케이션이 자식에 {@code @EnableAspectJAutoProxy}를 직접 선언해도 엔진의 creator로 바뀌어 부모
	 * advisor를 쓰지 않는다(이전 엔진에서는 누수가 났던 경우).
	 */
	@Test
	void childDeclaringAutoProxy() throws InterruptedException {
		assertPreviousLoadersCollected("user-auto-proxy", AUTO_PROXY, TRANSACTIONAL, true);
	}

	@Test
	void childDeclaringTransactionManagement() throws InterruptedException {
		assertPreviousLoadersCollected("user-tx-management", AUTO_PROXY + "\n" + CHILD_TX_MANAGEMENT, TRANSACTIONAL,
				true);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		CountingAspect countingAspect() {
			return new CountingAspect();
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new NoOpTransactionManager();
		}

	}

}
