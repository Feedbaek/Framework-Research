package com.example.reload.layout;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * 설정 배치 규칙을 검사한다: <b>인프라 설정은 부모에 두고, Advisor·BeanPostProcessor로 동작하는 {@code @Enable*}만
 * 자식에 둔다.</b>
 * <p>
 * 이 규칙을 어기면 오류 없이 기능이 빠진다. 자식의 인프라 설정은 부모 auto-configuration과 서블릿 컨테이너가 보지
 * 못하고, 부모의 Advisor·BeanPostProcessor는 자식 bean을 처리하지 않는다. 그래서 위반을 찾아 경고한다.
 * <ul>
 * <li>{@link #findChildViolations}: 자식 소유 {@code @Configuration}이 허용되지 않은 {@code @Enable*}이나
 * {@code @Bean} 메서드를 갖는 경우, 자식에 인프라 bean(Filter, Boot customizer, {@code SecurityFilterChain} 등)이
 * 정의된 경우.</li>
 * <li>{@link #findParentOnlyFeatures}: 부모에 선언되어 자식 bean에는 적용되지 않는 {@code @EnableAsync},
 * {@code @EnableScheduling}, Advisor bean. 트랜잭션·캐시 Advisor는 엔진이 자식에 다시 켜므로 제외한다.</li>
 * </ul>
 * 선택적 라이브러리 타입은 이름으로 비교하므로 클래스패스에 없어도 된다.
 */
public final class ConfigurationPlacementChecker {

	/**
	 * 자식 설정에 선언해도 되는 Advisor·BeanPostProcessor 기반 애너테이션.
	 */
	public static final Set<String> ALLOWED_CHILD_ANNOTATIONS = Set.of(
			"org.springframework.scheduling.annotation.EnableAsync",
			"org.springframework.scheduling.annotation.EnableScheduling",
			"org.springframework.context.annotation.EnableAspectJAutoProxy",
			"org.springframework.boot.context.properties.EnableConfigurationProperties",
			"org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity",
			"org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity",
			"org.springframework.retry.annotation.EnableRetry", "org.springframework.kafka.annotation.EnableKafka",
			"org.springframework.amqp.rabbit.annotation.EnableRabbit", "org.springframework.jms.annotation.EnableJms");

	/**
	 * 자식에 선언하면 안 되지만 이유가 따로 있는 애너테이션 → 안내.
	 */
	private static final Map<String, String> CHILD_ANNOTATION_HINTS = Map.of(
			"org.springframework.cache.annotation.EnableCaching",
			"declare it in the parent; the parent's auto-configuration then creates the CacheManager and the engine "
					+ "enables caching in every generation",
			"org.springframework.transaction.annotation.EnableTransactionManagement",
			"define the TransactionManager in the parent; the engine then enables transactions in every generation",
			"org.springframework.web.servlet.config.annotation.EnableWebMvc",
			"the engine already enables Spring MVC in every generation; remove it");

	private static final List<InfrastructureType> INFRASTRUCTURE_TYPES = List.of(
			new InfrastructureType("jakarta.servlet.Filter",
					"is not registered with the servlet container (only the parent registers servlet components)"),
			new InfrastructureType("org.springframework.boot.web.servlet.ServletContextInitializer",
					"is not applied to the servlet container (only the parent registers servlet components)"),
			new InfrastructureType("jakarta.servlet.ServletContextListener",
					"is not registered with the servlet container"),
			new InfrastructureType("jakarta.servlet.http.HttpSessionListener",
					"is not registered with the servlet container"),
			new InfrastructureType("jakarta.servlet.ServletRequestListener",
					"is not registered with the servlet container"),
			new InfrastructureType("org.springframework.boot.web.server.WebServerFactoryCustomizer",
					"is not applied to the embedded web server (the parent creates it)"),
			new InfrastructureType("org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer",
					"is not applied to the parent ObjectMapper that every generation copies"),
			new InfrastructureType("org.springframework.security.web.SecurityFilterChain",
					"is not added to the servlet filter chain (the parent's springSecurityFilterChain is used)"),
			new InfrastructureType("org.springframework.cache.CacheManager",
					"is recreated in every generation and not seen by the parent's auto-configuration"),
			new InfrastructureType("org.springframework.transaction.TransactionManager",
					"is recreated in every generation and does not enable @Transactional in the generation"),
			new InfrastructureType("javax.sql.DataSource",
					"is recreated in every generation and not seen by the parent's auto-configuration"),
			new InfrastructureType("org.springframework.boot.ApplicationRunner",
					"is not run (SpringApplication runs only the parent's runners)"),
			new InfrastructureType("org.springframework.boot.CommandLineRunner",
					"is not run (SpringApplication runs only the parent's runners)"),
			new InfrastructureType("org.springframework.http.converter.HttpMessageConverter",
					"is not added to the generation's MVC message converters"));

	/**
	 * 엔진이 자식에 다시 켜 주는 부모 Advisor.
	 */
	private static final Set<String> ENGINE_REPLICATED_ADVISORS = Set.of(
			"org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor",
			"org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor",
			"org.springframework.cache.jcache.interceptor.BeanFactoryJCacheOperationSourceAdvisor");

	private ConfigurationPlacementChecker() {
	}

	/**
	 * 자식 context의 규칙 위반.
	 * @param beanFactory 자식(세대) context의 bean factory
	 * @param childOwned 클래스가 자식 소유(세대 클래스로더가 로드한 애플리케이션 클래스)인지
	 * @param extraAllowedAnnotations {@link #ALLOWED_CHILD_ANNOTATIONS}에 더해 허용할 애너테이션 이름
	 * @return 위반 설명(순서 유지, 중복 없음)
	 */
	public static List<String> findChildViolations(ConfigurableListableBeanFactory beanFactory,
			Predicate<Class<?>> childOwned, Collection<String> extraAllowedAnnotations) {
		Set<String> allowed = new LinkedHashSet<>(ALLOWED_CHILD_ANNOTATIONS);
		allowed.addAll(extraAllowedAnnotations);
		Set<String> violations = new LinkedHashSet<>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			Class<?> type = userClass(beanFactory.getType(beanName, false));
			Class<?> declaringConfiguration = childFactoryClass(beanFactory, beanName, childOwned);
			boolean childComponent = declaringConfiguration == null && type != null && childOwned.test(type);
			if (childComponent && AnnotatedElementUtils.hasAnnotation(type, Configuration.class)) {
				checkConfigurationAnnotations(type, allowed, violations);
			}
			if (declaringConfiguration == null && !childComponent) {
				continue;
			}
			String origin = (declaringConfiguration != null)
					? declaringConfiguration.getName() + "#" + factoryMethodName(beanFactory, beanName) + "()"
					: type.getName();
			InfrastructureType infrastructure = InfrastructureType.match(type);
			if (infrastructure != null) {
				violations.add(origin + " [" + infrastructure.simpleName() + "] " + infrastructure.problem()
						+ "; define it in the parent");
			}
			else if (declaringConfiguration != null && !isAdvisorOrPostProcessor(type)) {
				violations.add(origin + ": a child configuration may only define Advisor or BeanPostProcessor "
						+ "beans; define other beans in the parent or as @Component classes");
			}
		}
		return List.copyOf(violations);
	}

	/**
	 * 부모에 선언되어 자식 bean에는 적용되지 않는 기능.
	 * @param beanFactory 부모 context의 bean factory
	 */
	public static List<String> findParentOnlyFeatures(ConfigurableListableBeanFactory beanFactory) {
		List<String> findings = new ArrayList<>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			Class<?> type = userClass(beanFactory.getType(beanName, false));
			if (type == null) {
				continue;
			}
			if (isOfType(type, "org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor")) {
				findings.add("@EnableAsync is declared in the parent: @Async methods of generation beans run "
						+ "synchronously; declare @EnableAsync in a child configuration");
			}
			else if (isOfType(type, "org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor")) {
				findings.add("@EnableScheduling is declared in the parent: @Scheduled methods of generation beans "
						+ "do not run; declare @EnableScheduling in a child configuration");
			}
			else if (Advisor.class.isAssignableFrom(type)
					&& ENGINE_REPLICATED_ADVISORS.stream().noneMatch((name) -> isOfType(type, name))) {
				findings.add("Advisor bean '" + beanName + "' (" + type.getName() + ") is defined in the parent "
						+ "and is not applied to generation beans; register it in a child configuration (for "
						+ "@EnableMethodSecurity or @EnableRetry, declare the annotation in a child configuration)");
			}
		}
		return findings;
	}

	private static void checkConfigurationAnnotations(Class<?> configuration, Set<String> allowed,
			Set<String> violations) {
		for (Annotation annotation : configuration.getAnnotations()) {
			Class<? extends Annotation> annotationType = annotation.annotationType();
			if (!isImporting(annotationType) || allowed.contains(annotationType.getName())) {
				continue;
			}
			String hint = CHILD_ANNOTATION_HINTS.getOrDefault(annotationType.getName(),
					"infrastructure configuration belongs in the parent; a child configuration may only declare "
							+ "Advisor or BeanPostProcessor based @Enable* annotations (add it to "
							+ "reload.placement-check.allowed-child-annotations if it is one)");
			violations.add(configuration.getName() + " @" + annotationType.getSimpleName() + ": " + hint);
		}
	}

	/**
	 * 설정을 가져오는 애너테이션인지({@code @Import}, {@code @ImportResource} 자체이거나 그것이 붙은
	 * {@code @Enable*}).
	 */
	private static boolean isImporting(Class<? extends Annotation> annotationType) {
		return annotationType == Import.class || annotationType == ImportResource.class
				|| AnnotatedElementUtils.hasAnnotation(annotationType, Import.class)
				|| AnnotatedElementUtils.hasAnnotation(annotationType, ImportResource.class);
	}

	/**
	 * {@code @Bean} 메서드로 정의된 bean이면, 그 메서드를 선언한 자식 소유 설정 클래스. 아니면 {@code null}.
	 */
	private static Class<?> childFactoryClass(ConfigurableListableBeanFactory beanFactory, String beanName,
			Predicate<Class<?>> childOwned) {
		BeanDefinition definition;
		try {
			definition = beanFactory.getMergedBeanDefinition(beanName);
		}
		catch (NoSuchBeanDefinitionException ex) {
			return null;
		}
		if (definition.getFactoryMethodName() == null) {
			return null;
		}
		Class<?> factoryClass;
		if (definition.getFactoryBeanName() != null) {
			factoryClass = userClass(beanFactory.getType(definition.getFactoryBeanName(), false));
		}
		else {
			String className = definition.getBeanClassName();
			factoryClass = (className != null) ? ClassUtils.resolveClassName(className, beanFactory.getBeanClassLoader())
					: null;
		}
		if (factoryClass == null || !childOwned.test(factoryClass)) {
			return null;
		}
		Method factoryMethod = ReflectionUtils.findMethod(factoryClass, definition.getFactoryMethodName(),
				(Class<?>[]) null);
		return (factoryMethod == null || AnnotatedElementUtils.hasAnnotation(factoryMethod, Bean.class)) ? factoryClass
				: null;
	}

	private static String factoryMethodName(ConfigurableListableBeanFactory beanFactory, String beanName) {
		return beanFactory.getMergedBeanDefinition(beanName).getFactoryMethodName();
	}

	private static boolean isAdvisorOrPostProcessor(Class<?> type) {
		return type != null && (Advisor.class.isAssignableFrom(type) || BeanPostProcessor.class.isAssignableFrom(type));
	}

	private static Class<?> userClass(Class<?> type) {
		return (type != null) ? ClassUtils.getUserClass(type) : null;
	}

	/**
	 * 클래스가 이름으로 지정한 타입(클래스 또는 인터페이스)인지. 선택적 라이브러리 타입을 로드하지 않고 비교한다.
	 */
	static boolean isOfType(Class<?> type, String typeName) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			if (current.getName().equals(typeName)) {
				return true;
			}
		}
		return ClassUtils.getAllInterfacesForClassAsSet(type).stream().anyMatch((i) -> i.getName().equals(typeName));
	}

	private record InfrastructureType(String typeName, String problem) {

		String simpleName() {
			return this.typeName.substring(this.typeName.lastIndexOf('.') + 1);
		}

		static InfrastructureType match(Class<?> type) {
			if (type == null) {
				return null;
			}
			return INFRASTRUCTURE_TYPES.stream().filter((candidate) -> isOfType(type, candidate.typeName())).findFirst()
				.orElse(null);
		}

	}

}
