package com.example.reload.layout;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.example.reload.fixture.NoOpTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 배치 규칙 검사. 이 테스트에서는 이름이 {@code Child}로 시작하는 중첩 클래스를 자식 소유로 본다.
 */
class ConfigurationPlacementCheckerTest {

	private static final Predicate<Class<?>> CHILD_OWNED = (type) -> type.getSimpleName().startsWith("Child");

	@Test
	void childConfigurationWithAllowedEnableAnnotationsOnlyPasses() {
		assertThat(childViolations(List.of(), ChildAsyncConfig.class)).isEmpty();
	}

	@Test
	void advisorAndPostProcessorBeanMethodsAreAllowed() {
		assertThat(childViolations(List.of(), ChildAdvisorConfig.class)).isEmpty();
	}

	@Test
	void componentsAndBeansOfNonChildClassesAreIgnored() {
		assertThat(childViolations(List.of(), ChildService.class, SharedInfrastructureConfig.class)).isEmpty();
	}

	@Test
	void plainBeanMethodInChildConfigurationIsReported() {
		assertThat(childViolations(List.of(), ChildSupplierConfig.class)).singleElement()
			.asString()
			.startsWith(ChildSupplierConfig.class.getName() + "#greetingSupplier()")
			.contains("may only define Advisor or BeanPostProcessor beans");
	}

	@Test
	void infrastructureBeanMethodIsReportedWithReason() {
		assertThat(childViolations(List.of(), ChildJacksonConfig.class)).singleElement()
			.asString()
			.startsWith(ChildJacksonConfig.class.getName() + "#snakeCase() [Jackson2ObjectMapperBuilderCustomizer]")
			.contains("is not applied to the parent ObjectMapper");
	}

	@Test
	void infrastructureComponentIsReportedWithReason() {
		assertThat(childViolations(List.of(), ChildHeaderFilter.class)).singleElement()
			.asString()
			.startsWith(ChildHeaderFilter.class.getName() + " [Filter]")
			.contains("is not registered with the servlet container");
	}

	@Test
	void enableAnnotationsThatBelongInTheParentAreReportedWithHints() {
		List<String> violations = childViolations(List.of(), ChildCachingConfig.class, SharedInfrastructureConfig.class);

		assertThat(violations).anySatisfy((violation) -> assertThat(violation)
			.startsWith(ChildCachingConfig.class.getName() + " @EnableCaching")
			.contains("declare it in the parent"));
		assertThat(violations).anySatisfy((violation) -> assertThat(violation)
			.startsWith(ChildCachingConfig.class.getName() + " @EnableTransactionManagement")
			.contains("define the TransactionManager in the parent"));
	}

	@Test
	void importAndUnknownEnableAnnotationsAreReported() {
		List<String> violations = childViolations(List.of(), ChildImportingConfig.class);

		assertThat(violations).anySatisfy((violation) -> assertThat(violation)
			.startsWith(ChildImportingConfig.class.getName() + " @Import"));
		assertThat(violations).anySatisfy((violation) -> assertThat(violation)
			.startsWith(ChildImportingConfig.class.getName() + " @EnableCustomInfrastructure")
			.contains("reload.placement-check.allowed-child-annotations"));
	}

	@Test
	void extraAllowedAnnotationIsAccepted() {
		List<String> violations = childViolations(List.of(EnableCustomInfrastructure.class.getName()),
				ChildImportingConfig.class);

		assertThat(violations).noneSatisfy((violation) -> assertThat(violation).contains("@EnableCustomInfrastructure"));
	}

	@Test
	void parentAsyncSchedulingAndAdvisorsAreReportedButEngineReplicatedAdvisorsAreNot() {
		try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext(ParentConfig.class)) {
			List<String> findings = ConfigurationPlacementChecker.findParentOnlyFeatures(parent.getBeanFactory());

			assertThat(findings).anySatisfy((finding) -> assertThat(finding).startsWith("@EnableAsync is declared"));
			assertThat(findings)
				.anySatisfy((finding) -> assertThat(finding).startsWith("@EnableScheduling is declared"));
			assertThat(findings).anySatisfy((finding) -> assertThat(finding).startsWith("Advisor bean 'auditAdvisor'"));
			assertThat(findings).noneSatisfy((finding) -> assertThat(finding).contains("TransactionAttributeSource"));
			assertThat(findings).hasSize(3);
		}
	}

	private static List<String> childViolations(List<String> extraAllowed, Class<?>... componentClasses) {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(componentClasses)) {
			return ConfigurationPlacementChecker.findChildViolations(context.getBeanFactory(), CHILD_OWNED,
					extraAllowed);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAsync
	@EnableScheduling
	static class ChildAsyncConfig {

	}

	@Configuration(proxyBeanMethods = false)
	static class ChildAdvisorConfig {

		@Bean
		static Advisor auditAdvisor() {
			return new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(Deprecated.class),
					(MethodInterceptor) (invocation) -> invocation.proceed());
		}

		@Bean
		static BeanPostProcessor noOpPostProcessor() {
			return new BeanPostProcessor() {
			};
		}

	}

	@Component
	static class ChildService {

	}

	/**
	 * 자식 소유가 아닌 설정(부모 또는 라이브러리). 검사 대상이 아니다.
	 */
	@Configuration(proxyBeanMethods = false)
	static class SharedInfrastructureConfig {

		@Bean
		CacheManager cacheManager() {
			return new ConcurrentMapCacheManager();
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new NoOpTransactionManager();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ChildSupplierConfig {

		@Bean
		Supplier<String> greetingSupplier() {
			return () -> "hello";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ChildJacksonConfig {

		@Bean
		Jackson2ObjectMapperBuilderCustomizer snakeCase() {
			return (builder) -> {
			};
		}

	}

	@Component
	static class ChildHeaderFilter implements Filter {

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, jakarta.servlet.ServletException {
			chain.doFilter(request, response);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@EnableCaching
	@EnableTransactionManagement
	static class ChildCachingConfig {

	}

	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@Import(CustomInfrastructureConfiguration.class)
	@interface EnableCustomInfrastructure {

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomInfrastructureConfiguration {

	}

	@Configuration(proxyBeanMethods = false)
	@Import(CustomInfrastructureConfiguration.class)
	@EnableCustomInfrastructure
	static class ChildImportingConfig {

	}

	@Configuration(proxyBeanMethods = false)
	@EnableAsync
	@EnableScheduling
	@EnableTransactionManagement
	static class ParentConfig {

		@Bean
		PlatformTransactionManager transactionManager() {
			return new NoOpTransactionManager();
		}

		@Bean
		static Advisor auditAdvisor() {
			return new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(Deprecated.class),
					(MethodInterceptor) (invocation) -> invocation.proceed());
		}

	}

}
