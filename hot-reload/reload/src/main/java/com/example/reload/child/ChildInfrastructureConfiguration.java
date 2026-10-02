package com.example.reload.child;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.util.ClassUtils;

/**
 * 모든 자식 세대에 register되는 AOP 인프라. 자식에는 Spring Boot auto-configuration이 없으므로, 부모에서
 * auto-configuration이 켜 주는 기능 중 자식 bean에도 필요한 것을 엔진이 골라 켠다.
 * <ul>
 * <li>auto-proxy: AspectJ가 있으면 {@link ChildAspectJAutoProxyCreator}(부모 {@code @Aspect}는 적용, 부모
 * {@code Advisor} bean은 적용하지 않음). 부모 Boot의 {@code AopAutoConfiguration}처럼 기본은 클래스 프록시.</li>
 * <li>트랜잭션: 부모에 {@code TransactionManager}가 있으면 자식에도 {@code @EnableTransactionManagement}.</li>
 * <li>캐시: 부모에 캐시 인터셉터({@code @EnableCaching})가 있으면 자식에도 {@code @EnableCaching}.</li>
 * <li>메서드 검증: {@code jakarta.validation.Validator}가 클래스패스에 있으면
 * {@code MethodValidationPostProcessor}(클래스 프록시).</li>
 * </ul>
 * 트랜잭션 매니저와 {@code CacheManager}는 부모 bean을 그대로 쓴다. {@code @Async}, {@code @Scheduled}처럼
 * BeanPostProcessor로 동작하는 그 밖의 기능은 켜지 않는다. 필요하면 애플리케이션이 자식 패키지에
 * {@code @Enable*}을 선언한다.
 */
@Configuration(proxyBeanMethods = false)
public class ChildInfrastructureConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "jakarta.validation.Validator")
	static class ChildValidationConfiguration {
		@Bean
		static org.springframework.validation.beanvalidation.MethodValidationPostProcessor methodValidationPostProcessor() {
			var processor = new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
			processor.setProxyTargetClass(true);
			return processor;
		}
	}

	@Bean
	static ChildAutoProxyCreatorRegistrar childAutoProxyCreatorRegistrar() {
		return new ChildAutoProxyCreatorRegistrar();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(name = "org.springframework.transaction.PlatformTransactionManager")
	@ConditionalOnBean(type = "org.springframework.transaction.TransactionManager")
	@EnableTransactionManagement(proxyTargetClass = true)
	static class ChildTransactionConfiguration {

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnBean(type = "org.springframework.cache.interceptor.CacheAspectSupport")
	@EnableCaching(proxyTargetClass = true)
	static class ChildCachingConfiguration {

	}

	/**
	 * 자식의 auto-proxy creator를 {@link ChildAspectJAutoProxyCreator}로 등록하거나 바꾼다.
	 * <p>
	 * 설정 클래스 처리({@code ConfigurationClassPostProcessor})가 끝난 뒤에 실행되므로, 애플리케이션이 자식
	 * 패키지에 {@code @EnableAspectJAutoProxy}를 선언해 기본 creator가 등록된 경우에도 교체된다.
	 */
	static class ChildAutoProxyCreatorRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

		private static final String ASPECTJ_MARKER = "org.aspectj.weaver.Advice";

		private Environment environment;

		@Override
		public void setEnvironment(Environment environment) {
			this.environment = environment;
		}

		@Override
		public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
			if (!ClassUtils.isPresent(ASPECTJ_MARKER, getClass().getClassLoader())) {
				// AspectJ가 없으면 트랜잭션·캐시가 InfrastructureAdvisorAutoProxyCreator를 등록한다.
				// 그 creator는 원래 자기 context에 정의된 advisor만 쓴다.
				return;
			}
			boolean autoProxy = this.environment.getProperty("spring.aop.auto", Boolean.class, true);
			if (autoProxy) {
				AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry);
				if (this.environment.getProperty("spring.aop.proxy-target-class", Boolean.class, true)) {
					AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
				}
			}
			if (registry.containsBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)) {
				BeanDefinition definition = registry.getBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME);
				if (AnnotationAwareAspectJAutoProxyCreator.class.getName().equals(definition.getBeanClassName())) {
					definition.setBeanClassName(ChildAspectJAutoProxyCreator.class.getName());
				}
			}
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		}

	}

}
