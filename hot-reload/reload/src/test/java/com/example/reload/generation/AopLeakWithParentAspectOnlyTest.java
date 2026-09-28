package com.example.reload.generation;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RestController;

import com.example.reload.fixture.CountingAspect;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모: 공용 {@code @Aspect}와 사용자 정의 {@code Advisor} bean. 트랜잭션·캐시는 없다.
 */
@SpringBootTest(classes = AopLeakWithParentAspectOnlyTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class AopLeakWithParentAspectOnlyTest extends AbstractAopLeakTest {

	private static final Path WORK_DIRECTORY = createWorkDirectory("aop-parent-aspect");

	private static final ControllerCompiler compiler = new ControllerCompiler(WORK_DIRECTORY,
			WORK_DIRECTORY.resolve("classes"));

	@Autowired
	private AtomicInteger parentAdvisorInvocations;

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
	 * 애플리케이션이 자식에 아무 설정도 하지 않아도 엔진의 auto-proxy creator가 부모 aspect를 적용한다.
	 */
	@Test
	void parentAspectAppliedToChild() throws InterruptedException {
		assertPreviousLoadersCollected("parent-aspect", NONE, NONE, false);
	}

	/**
	 * 부모의 {@code Advisor} bean은 자식에 적용되지 않는다(누수를 막기 위한 엔진 정책). 같은 요청에서 부모
	 * {@code @Aspect}는 적용된다.
	 */
	@Test
	void parentAdvisorBeanNotAppliedToChild() {
		int aspectBefore = parentAspectInvocations();
		int advisorBefore = this.parentAdvisorInvocations.get();

		deployAndCall("parent-advisor", NONE, NONE);

		assertThat(parentAspectInvocations()).isGreaterThan(aspectBefore);
		assertThat(this.parentAdvisorInvocations.get()).isEqualTo(advisorBefore);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		CountingAspect countingAspect() {
			return new CountingAspect();
		}

		@Bean
		AtomicInteger parentAdvisorInvocations() {
			return new AtomicInteger();
		}

		@Bean
		Advisor parentCountingAdvisor(AtomicInteger parentAdvisorInvocations) {
			MethodInterceptor interceptor = (invocation) -> {
				parentAdvisorInvocations.incrementAndGet();
				return invocation.proceed();
			};
			return new DefaultPointcutAdvisor(new AnnotationMatchingPointcut(RestController.class, true), interceptor);
		}

	}

}
