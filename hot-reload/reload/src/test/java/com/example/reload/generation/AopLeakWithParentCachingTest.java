package com.example.reload.generation;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.reload.fixture.CountingAspect;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모: 공용 aspect + {@code @EnableCaching}(부모에 캐시 advisor가 등록된다). 트랜잭션 외의 Advisor bean
 * 방식 AOP에서도 누수가 없는지 확인한다.
 * <p>
 * 캐시에 값이 저장되면 세대가 바뀌어도 이전 응답이 나오므로 {@code condition = "false"}로 저장은 막는다.
 * advisor의 메타데이터·조건식 처리 경로는 그대로 탄다.
 */
@SpringBootTest(classes = AopLeakWithParentCachingTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class AopLeakWithParentCachingTest extends AbstractAopLeakTest {

	private static final String CACHEABLE = "@org.springframework.cache.annotation.Cacheable(cacheNames = \"aop\", condition = \"false\")";

	private static final Path WORK_DIRECTORY = createWorkDirectory("aop-parent-cache");

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

	/**
	 * 엔진이 켠 자식 캐시 인프라가 {@code @Cacheable}을 처리한다. 부모 캐시 advisor의 캐시에는 자식 클래스가
	 * 남지 않는다.
	 */
	@Test
	void childCacheableUsesEngineProvidedInfrastructure() throws InterruptedException {
		ApplicationContext child = deployAndCall("engine-cache-check", NONE, CACHEABLE);
		assertThat(child.containsBean("cacheInterceptor")).isTrue();
		assertThat(((ConfigurableApplicationContext) child).getBeanFactory().containsLocalBean("cacheInterceptor"))
			.as("child has its own cache interceptor")
			.isTrue();
		child = null;
		assertThat(LeakDiagnostics.describe(this.parentContext)).contains("no parent-side map references child");

		assertPreviousLoadersCollected("engine-cache", NONE, CACHEABLE, false);
	}

	/**
	 * 애플리케이션이 자식에 {@code @EnableAspectJAutoProxy}를 직접 선언한 경우(이전 엔진에서는 누수가 났던 경우).
	 */
	@Test
	void childDeclaringAutoProxy() throws InterruptedException {
		assertPreviousLoadersCollected("user-auto-proxy", AUTO_PROXY, CACHEABLE, false);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableCaching
	static class ParentApplication {

		@Bean
		CountingAspect countingAspect() {
			return new CountingAspect();
		}

	}

}
