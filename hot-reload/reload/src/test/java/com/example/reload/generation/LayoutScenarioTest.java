package com.example.reload.generation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.example.reload.fixture.KnownIssue;

import static com.example.reload.generation.ConsumerApp.sources;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 부모·자식에 어떤 bean을 둘지(배치)에 따라 AOP와 설정이 어떻게 적용되는지 비교한다. 모두 {@link ConsumerApp}으로
 * 실제 소비자 앱과 같은 배치(부모·자식이 같은 출력 디렉터리)로 띄운다.
 * <ul>
 * <li>{@link ParentComponentsWithChildInfrastructure}: 부모에 일반 {@code @Component}, 자식에 AOP·설정 bean</li>
 * <li>{@link ParentInfrastructureWithChildComponents}: 부모에 인프라 설정·{@code @Aspect}, 자식에 컨트롤러·서비스
 * (권장 배치)</li>
 * <li>{@link AdvisorPlacement}: {@code Advisor}를 부모에 두는 경우와 자식 설정에서 등록하는 경우</li>
 * </ul>
 */
class LayoutScenarioTest {

	/**
	 * 부모 패키지({@code com.example.app.core})에는 {@code @Component} bean만, AOP({@code @Aspect})와 설정
	 * ({@code @Configuration}, {@code @Enable*}, 인프라 bean)은 자식에 둔다.
	 */
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	class ParentComponentsWithChildInfrastructure {

		private static final String PARENT_COMPONENT = """
				package com.example.app.core;

				import java.util.concurrent.CompletableFuture;
				import java.util.concurrent.atomic.AtomicInteger;

				import org.springframework.cache.annotation.Cacheable;
				import org.springframework.scheduling.annotation.Async;
				import org.springframework.stereotype.Component;
				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.transaction.support.TransactionSynchronizationManager;

				@Component
				public class PricingService {

					private final AtomicInteger calls = new AtomicInteger();

					public String label() {
						return "parent";
					}

					@Transactional
					public boolean inTransaction() {
						return TransactionSynchronizationManager.isActualTransactionActive();
					}

					@Cacheable("parent-prices")
					public String cachedCall() {
						return "call-" + this.calls.incrementAndGet();
					}

					@Async
					public CompletableFuture<String> threadName() {
						return CompletableFuture.completedFuture(Thread.currentThread().getName());
					}

				}
				""";

		private static final String CHILD_SERVICE = """
				package com.example.app.service;

				import java.util.concurrent.CompletableFuture;
				import java.util.concurrent.atomic.AtomicInteger;

				import org.springframework.cache.annotation.Cacheable;
				import org.springframework.scheduling.annotation.Async;
				import org.springframework.stereotype.Service;
				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.transaction.support.TransactionSynchronizationManager;

				@Service
				public class ChildService {

					private final AtomicInteger calls = new AtomicInteger();

					public String label() {
						return "child";
					}

					@Transactional
					public boolean inTransaction() {
						return TransactionSynchronizationManager.isActualTransactionActive();
					}

					@Cacheable("child-prices")
					public String cachedCall() {
						return "call-" + this.calls.incrementAndGet();
					}

					@Async
					public CompletableFuture<String> threadName() {
						return CompletableFuture.completedFuture(Thread.currentThread().getName());
					}

				}
				""";

		private static final String CHILD_ASPECT = """
				package com.example.app.aop;

				import org.aspectj.lang.ProceedingJoinPoint;
				import org.aspectj.lang.annotation.Around;
				import org.aspectj.lang.annotation.Aspect;
				import org.springframework.stereotype.Component;

				@Aspect
				@Component
				public class MarkingAspect {

					@Around("execution(String com.example.app.core..*.label()) || execution(String com.example.app.service..*.label())")
					public Object mark(ProceedingJoinPoint joinPoint) throws Throwable {
						return "[aspect]" + joinPoint.proceed();
					}

				}
				""";

		private static final String CHILD_CONFIG = """
				package com.example.app.config;

				import org.springframework.cache.CacheManager;
				import org.springframework.cache.annotation.EnableCaching;
				import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;
				import org.springframework.scheduling.annotation.EnableAsync;
				import org.springframework.transaction.PlatformTransactionManager;

				import com.example.reload.fixture.NoOpTransactionManager;

				@Configuration(proxyBeanMethods = false)
				@EnableCaching
				@EnableAsync
				public class AppConfig {

					@Bean
					PlatformTransactionManager transactionManager() {
						return new NoOpTransactionManager();
					}

					@Bean
					CacheManager cacheManager() {
						return new ConcurrentMapCacheManager();
					}

				}
				""";

		private static final String CONTROLLER = """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.core.PricingService;
				import com.example.app.service.ChildService;

				@RestController
				public class LayoutController {

					private final PricingService parent;

					private final ChildService child;

					public LayoutController(PricingService parent, ChildService child) {
						this.parent = parent;
						this.child = child;
					}

					@GetMapping("/parent/label")
					public String parentLabel() {
						return this.parent.label();
					}

					@GetMapping("/child/label")
					public String childLabel() {
						return this.child.label();
					}

					@GetMapping("/parent/tx")
					public boolean parentTx() {
						return this.parent.inTransaction();
					}

					@GetMapping("/child/tx")
					public boolean childTx() {
						return this.child.inTransaction();
					}

					@GetMapping("/parent/cached")
					public String parentCached() {
						return this.parent.cachedCall();
					}

					@GetMapping("/child/cached")
					public String childCached() {
						return this.child.cachedCall();
					}

					@GetMapping("/parent/async")
					public String parentAsync() {
						return threadKind(this.parent.threadName().join());
					}

					@GetMapping("/child/async")
					public String childAsync() {
						return threadKind(this.child.threadName().join());
					}

					private static String threadKind(String threadName) {
						return threadName.equals(Thread.currentThread().getName()) ? "same-thread" : "other-thread";
					}

				}
				""";

		private ConsumerApp app;

		@BeforeAll
		void start() {
			this.app = ConsumerApp.start(
					sources(PARENT_COMPONENT, CHILD_SERVICE, CHILD_ASPECT, CHILD_CONFIG, CONTROLLER),
					List.of("--reload.parent-packages=com.example.app.core"));
		}

		@AfterAll
		void stop() throws Exception {
			this.app.close();
		}

		@Test
		void childAspectAppliesToChildService() {
			assertThat(this.app.getOk("/child/label")).isEqualTo("[aspect]child");
		}

		@Test
		@KnownIssue("부모 bean은 부모 context에서 만들어지므로 자식 @Aspect가 적용되지 않는다")
		void childAspectAppliesToParentComponent() {
			assertThat(this.app.getOk("/parent/label")).isEqualTo("[aspect]parent");
		}

		/**
		 * 일반 Boot 앱은 {@code TransactionManager} bean이 있으면 {@code TransactionAutoConfiguration}이 트랜잭션을
		 * 켠다.
		 */
		@Test
		@KnownIssue("엔진의 ChildTransactionConfiguration(@ConditionalOnBean(TransactionManager))이 자식 설정에 정의한 "
				+ "TransactionManager를 보지 못해 자식 @Transactional도 켜지지 않는다. 자식에 "
				+ "@EnableTransactionManagement를 직접 선언하면 된다(childExplicitTransactionManagementWorks)")
		void childTransactionManagerAppliesToChildTransactional() {
			assertThat(this.app.getOk("/child/tx")).isEqualTo("true");
		}

		@Test
		void childExplicitTransactionManagementWorks() throws Exception {
			String config = CHILD_CONFIG.replace("@EnableAsync",
					"@EnableAsync\n@org.springframework.transaction.annotation.EnableTransactionManagement");
			try (ConsumerApp explicit = ConsumerApp.start(
					sources(PARENT_COMPONENT, CHILD_SERVICE, CHILD_ASPECT, config, CONTROLLER),
					List.of("--reload.parent-packages=com.example.app.core"))) {
				assertThat(explicit.getOk("/child/tx")).isEqualTo("true");
				assertThat(explicit.getOk("/parent/tx")).as("parent component is still not transactional")
					.isEqualTo("false");
			}
		}

		@Test
		@KnownIssue("부모 bean은 자식의 트랜잭션 인프라가 처리하지 않고, 부모에는 TransactionManager가 없어 부모 "
				+ "Boot도 트랜잭션을 켜지 않는다")
		void transactionalOnParentComponentUsesChildTransactionManager() {
			assertThat(this.app.getOk("/parent/tx")).isEqualTo("true");
		}

		/**
		 * 자식 설정에 {@code CacheManager}까지 두면 캐시가 세대와 함께 사라지므로, 부모 캐시에 자식 객체가 남는
		 * 문제(문서 4장)도 없다.
		 */
		@Test
		void childCacheManagerAppliesToChildCacheable() {
			String first = this.app.getOk("/child/cached");

			assertThat(this.app.getOk("/child/cached")).isEqualTo(first);
		}

		@Test
		@KnownIssue("자식의 @EnableCaching은 부모 bean을 처리하지 않아 부모 @Cacheable이 무시된다")
		void cacheableOnParentComponentUsesChildCacheManager() {
			String first = this.app.getOk("/parent/cached");

			assertThat(this.app.getOk("/parent/cached")).isEqualTo(first);
		}

		@Test
		void childAsyncAppliesToChildService() {
			assertThat(this.app.getOk("/child/async")).isEqualTo("other-thread");
		}

		@Test
		@KnownIssue("자식의 @EnableAsync는 부모 bean을 처리하지 않아 부모 @Async가 동기로 실행된다")
		void asyncOnParentComponentRunsOnAnotherThread() {
			assertThat(this.app.getOk("/parent/async")).isEqualTo("other-thread");
		}

		@Test
		void childInfrastructureStillAppliesAfterReload() {
			this.app.reloadSuccessfully();

			assertThat(this.app.getOk("/child/label")).isEqualTo("[aspect]child");
			assertThat(this.app.getOk("/child/cached")).isEqualTo(this.app.getOk("/child/cached"));
			assertThat(this.app.getOk("/child/async")).isEqualTo("other-thread");
		}

		/**
		 * 설정 bean(여기서는 {@code GreetingFormatter})을 자식 {@code @Configuration}에 정의하고, 부모 {@code @Component}가
		 * 주입받는다. 타입은 부모 패키지에 있어서 경계 위반은 아니다.
		 */
		@Test
		@KnownIssue("부모 context는 자식 context의 bean을 볼 수 없어 부모 @Component가 자식 설정 bean을 주입받지 못하고 "
				+ "애플리케이션이 뜨지 못한다")
		void parentComponentCanInjectBeanDefinedInChildConfiguration() {
			Map<String, String> sources = sources("""
					package com.example.app.core;

					public interface GreetingFormatter {

						String format(String name);

					}
					""", """
					package com.example.app.core;

					import org.springframework.stereotype.Component;

					@Component
					public class ReportService {

						private final GreetingFormatter formatter;

						public ReportService(GreetingFormatter formatter) {
							this.formatter = formatter;
						}

						public String report() {
							return this.formatter.format("report");
						}

					}
					""", """
					package com.example.app.config;

					import org.springframework.context.annotation.Bean;
					import org.springframework.context.annotation.Configuration;

					import com.example.app.core.GreetingFormatter;

					@Configuration(proxyBeanMethods = false)
					public class FormatterConfig {

						@Bean
						GreetingFormatter greetingFormatter() {
							return (name) -> "Hello, " + name;
						}

					}
					""");

			assertThatCode(() -> ConsumerApp
				.start(sources, List.of("--reload.parent-packages=com.example.app.core"))
				.close()).doesNotThrowAnyException();
		}

	}

	/**
	 * 권장 배치: 부모 패키지({@code com.example.app.infra})에 인프라 설정·공통 {@code @Aspect}·Filter를 두고,
	 * 애플리케이션 클래스에 {@code @EnableCaching}을 선언한다. 컨트롤러·서비스는 자식이다. 메서드 보안은 Advisor라
	 * 부모에 두면 자식에 적용되지 않으므로({@link AdvisorPlacement}) 자식 설정에 {@code @EnableMethodSecurity}를 둔다.
	 */
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	class ParentInfrastructureWithChildComponents {

		private static final String APPLICATION = """
				package com.example.app;

				import org.springframework.boot.autoconfigure.SpringBootApplication;
				import org.springframework.cache.annotation.EnableCaching;

				@SpringBootApplication
				@EnableCaching
				public class DemoApplication {
				}
				""";

		private static final String GREETING_FORMATTER = """
				package com.example.app.infra;

				public interface GreetingFormatter {

					String format(String name);

				}
				""";

		private static final String INFRA_CONFIG = """
				package com.example.app.infra;

				import com.fasterxml.jackson.databind.PropertyNamingStrategies;

				import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;
				import org.springframework.security.config.Customizer;
				import org.springframework.security.config.annotation.web.builders.HttpSecurity;
				import org.springframework.security.core.userdetails.User;
				import org.springframework.security.core.userdetails.UserDetailsService;
				import org.springframework.security.provisioning.InMemoryUserDetailsManager;
				import org.springframework.security.web.SecurityFilterChain;
				import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
				import org.springframework.transaction.PlatformTransactionManager;

				import com.example.reload.fixture.NoOpTransactionManager;

				@Configuration(proxyBeanMethods = false)
				public class InfraConfig {

					@Bean
					PlatformTransactionManager transactionManager() {
						return new NoOpTransactionManager();
					}

					@Bean
					GreetingFormatter greetingFormatter() {
						return (name) -> "Hello, " + name;
					}

					@Bean
					Jackson2ObjectMapperBuilderCustomizer snakeCase() {
						return (builder) -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
					}

					@Bean
					SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
						return http
							.authorizeHttpRequests((requests) -> requests
								.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/secure/**"))
								.authenticated()
								.anyRequest()
								.permitAll())
							.httpBasic(Customizer.withDefaults())
							.csrf((csrf) -> csrf.disable())
							.build();
					}

					@Bean
					UserDetailsService userDetailsService() {
						return new InMemoryUserDetailsManager(
								User.withUsername("user").password("{noop}password").roles("USER").build(),
								User.withUsername("admin").password("{noop}password").roles("USER", "ADMIN").build());
					}

				}
				""";

		private static final String INFRA_ASPECT = """
				package com.example.app.infra;

				import org.aspectj.lang.ProceedingJoinPoint;
				import org.aspectj.lang.annotation.Around;
				import org.aspectj.lang.annotation.Aspect;
				import org.springframework.stereotype.Component;

				@Aspect
				@Component
				public class MarkingAspect {

					@Around("execution(String com.example.app.service..*.label())")
					public Object mark(ProceedingJoinPoint joinPoint) throws Throwable {
						return "[aspect]" + joinPoint.proceed();
					}

				}
				""";

		private static final String INFRA_FILTER = """
				package com.example.app.infra;

				import java.io.IOException;

				import jakarta.servlet.FilterChain;
				import jakarta.servlet.ServletException;
				import jakarta.servlet.http.HttpServletRequest;
				import jakarta.servlet.http.HttpServletResponse;

				import org.springframework.stereotype.Component;
				import org.springframework.web.filter.OncePerRequestFilter;

				@Component
				public class HeaderFilter extends OncePerRequestFilter {

					@Override
					protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
							FilterChain chain) throws ServletException, IOException {
						response.setHeader("X-Infra-Filter", "applied");
						chain.doFilter(request, response);
					}

				}
				""";

		private static final String CHILD_SERVICE = """
				package com.example.app.service;

				import java.util.concurrent.atomic.AtomicInteger;

				import org.springframework.cache.annotation.Cacheable;
				import org.springframework.stereotype.Service;
				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.transaction.support.TransactionSynchronizationManager;

				import com.example.app.infra.GreetingFormatter;

				@Service
				public class ChildService {

					private final AtomicInteger calls = new AtomicInteger();

					private final GreetingFormatter formatter;

					public ChildService(GreetingFormatter formatter) {
						this.formatter = formatter;
					}

					public String label() {
						return "child";
					}

					public String greeting() {
						return this.formatter.format("kim");
					}

					@Transactional
					public boolean inTransaction() {
						return TransactionSynchronizationManager.isActualTransactionActive();
					}

					@Cacheable("child-prices")
					public String cachedCall() {
						return "call-" + this.calls.incrementAndGet();
					}

				}
				""";

		private static final String CHILD_METHOD_SECURITY = """
				package com.example.app.config;

				import org.springframework.context.annotation.Configuration;
				import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

				@Configuration(proxyBeanMethods = false)
				@EnableMethodSecurity
				public class MethodSecurityConfig {
				}
				""";

		private static final String CONTROLLER = """
				package com.example.app.web;

				import org.springframework.security.access.prepost.PreAuthorize;
				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.service.ChildService;

				@RestController
				public class LayoutController {

					private final ChildService service;

					public LayoutController(ChildService service) {
						this.service = service;
					}

					@GetMapping("/label")
					public String label() {
						return this.service.label();
					}

					@GetMapping("/greeting")
					public String greeting() {
						return this.service.greeting();
					}

					@GetMapping("/tx")
					public boolean tx() {
						return this.service.inTransaction();
					}

					@GetMapping("/cached")
					public String cached() {
						return this.service.cachedCall();
					}

					@GetMapping("/person")
					public Person person() {
						return new Person("Minseok");
					}

					@GetMapping("/secure/hello")
					public String secureHello() {
						return "secure";
					}

					@GetMapping("/secure/admin")
					@PreAuthorize("hasRole('ADMIN')")
					public String admin() {
						return "admin";
					}

					public record Person(String firstName) {
					}

				}
				""";

		private ConsumerApp app;

		@BeforeAll
		void start() {
			Map<String, String> sources = sources(GREETING_FORMATTER, INFRA_CONFIG, INFRA_ASPECT, INFRA_FILTER,
					CHILD_SERVICE, CHILD_METHOD_SECURITY, CONTROLLER);
			sources.put(ConsumerApp.APPLICATION_CLASS, APPLICATION);
			this.app = ConsumerApp.start(sources, List.of("--reload.parent-packages=com.example.app.infra",
					"--spring.autoconfigure.exclude="));
		}

		@AfterAll
		void stop() throws Exception {
			this.app.close();
		}

		@Test
		void parentAspectAppliesToChildService() {
			assertThat(this.app.getOk("/label")).isEqualTo("[aspect]child");
		}

		@Test
		void parentConfigurationBeanIsInjectedIntoChildService() {
			assertThat(this.app.getOk("/greeting")).isEqualTo("Hello, kim");
		}

		@Test
		void parentTransactionManagerAppliesToChildTransactional() {
			assertThat(this.app.getOk("/tx")).isEqualTo("true");
		}

		@Test
		void parentCacheConfigurationAppliesToChildCacheable() {
			String first = this.app.getOk("/cached");

			assertThat(this.app.getOk("/cached")).isEqualTo(first);
		}

		/**
		 * 부모의 customizer는 부모 {@code ObjectMapper}에 적용되고, 자식 MVC는 그 복사본을 쓴다.
		 */
		@Test
		void parentJacksonCustomizerAppliesToChildMvc() {
			assertThat(this.app.getOk("/person")).isEqualTo("{\"first_name\":\"Minseok\"}");
		}

		@Test
		void parentFilterAppliesToChildRequests() {
			assertThat(this.app.get("/label").headers().firstValue("X-Infra-Filter")).hasValue("applied");
		}

		@Test
		void parentSecurityFilterChainProtectsChildEndpoints() {
			assertThat(this.app.get("/secure/hello").statusCode()).isEqualTo(401);
			assertThat(this.app.getAs("user", "/secure/hello").statusCode()).isEqualTo(200);
		}

		@Test
		void methodSecurityEnabledInChildConfigurationIsEnforced() {
			assertThat(this.app.getAs("user", "/secure/admin").statusCode()).isEqualTo(403);
			assertThat(this.app.getAs("admin", "/secure/admin").statusCode()).isEqualTo(200);
		}

		@Test
		void infrastructureStillAppliesAfterReload() {
			this.app.reloadSuccessfully();

			assertThat(this.app.getOk("/label")).isEqualTo("[aspect]child");
			assertThat(this.app.getOk("/tx")).isEqualTo("true");
			assertThat(this.app.getOk("/person")).isEqualTo("{\"first_name\":\"Minseok\"}");
			assertThat(this.app.getAs("user", "/secure/admin").statusCode()).isEqualTo(403);
		}

		@Test
		void recommendedLayoutDoesNotPinPreviousGenerations() throws InterruptedException {
			this.app.assertPreviousGenerationsCollected(() -> {
				this.app.getOk("/label");
				this.app.getOk("/tx");
				this.app.getOk("/greeting");
				this.app.getOk("/person");
				assertThat(this.app.getAs("admin", "/secure/admin").statusCode()).isEqualTo(200);
			});
		}

	}

	/**
	 * {@code Advisor}를 어디에 두느냐. 부모 패키지({@code com.example.app.infra})에 {@code Advisor} 구현 클래스
	 * ({@code MarkingAdvisor}, 애너테이션이 붙은 메서드의 반환값 앞에 표시를 붙인다)와 애너테이션을 둔다.
	 * <ul>
	 * <li>부모 설정이 {@code @ParentAudited}용 Advisor bean을 등록한다.</li>
	 * <li>자식 설정이 같은 클래스로 {@code @ChildAudited}용 Advisor bean을 등록한다.</li>
	 * <li>애플리케이션 클래스(부모)에 {@code @EnableMethodSecurity}를 선언한다.</li>
	 * </ul>
	 */
	@Nested
	@TestInstance(Lifecycle.PER_CLASS)
	class AdvisorPlacement {

		private static final String APPLICATION = """
				package com.example.app;

				import org.springframework.boot.autoconfigure.SpringBootApplication;
				import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

				@SpringBootApplication
				@EnableMethodSecurity
				public class DemoApplication {
				}
				""";

		private static final String PARENT_AUDITED = """
				package com.example.app.infra;

				import java.lang.annotation.ElementType;
				import java.lang.annotation.Retention;
				import java.lang.annotation.RetentionPolicy;
				import java.lang.annotation.Target;

				@Target(ElementType.METHOD)
				@Retention(RetentionPolicy.RUNTIME)
				public @interface ParentAudited {
				}
				""";

		private static final String CHILD_AUDITED = """
				package com.example.app.infra;

				import java.lang.annotation.ElementType;
				import java.lang.annotation.Retention;
				import java.lang.annotation.RetentionPolicy;
				import java.lang.annotation.Target;

				@Target(ElementType.METHOD)
				@Retention(RetentionPolicy.RUNTIME)
				public @interface ChildAudited {
				}
				""";

		private static final String MARKING_ADVISOR = """
				package com.example.app.infra;

				import java.lang.annotation.Annotation;

				import org.aopalliance.aop.Advice;
				import org.aopalliance.intercept.MethodInterceptor;

				import org.springframework.aop.Pointcut;
				import org.springframework.aop.support.AbstractPointcutAdvisor;
				import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;

				/** 애너테이션이 붙은 메서드의 반환값 앞에 표시를 붙이는 Advisor. Spring 애너테이션은 없다. */
				public class MarkingAdvisor extends AbstractPointcutAdvisor {

					private final Pointcut pointcut;

					private final String mark;

					public MarkingAdvisor(Class<? extends Annotation> annotation, String mark) {
						this.pointcut = AnnotationMatchingPointcut.forMethodAnnotation(annotation);
						this.mark = mark;
					}

					@Override
					public Pointcut getPointcut() {
						return this.pointcut;
					}

					@Override
					public Advice getAdvice() {
						return (MethodInterceptor) (invocation) -> this.mark + invocation.proceed();
					}

				}
				""";

		private static final String PARENT_CONFIG = """
				package com.example.app.infra;

				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;
				import org.springframework.security.config.Customizer;
				import org.springframework.security.config.annotation.web.builders.HttpSecurity;
				import org.springframework.security.core.userdetails.User;
				import org.springframework.security.core.userdetails.UserDetailsService;
				import org.springframework.security.provisioning.InMemoryUserDetailsManager;
				import org.springframework.security.web.SecurityFilterChain;

				@Configuration(proxyBeanMethods = false)
				public class ParentConfig {

					@Bean
					static MarkingAdvisor parentAdvisor() {
						return new MarkingAdvisor(ParentAudited.class, "[parent-advisor]");
					}

					@Bean
					SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
						return http.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
							.httpBasic(Customizer.withDefaults())
							.csrf((csrf) -> csrf.disable())
							.build();
					}

					@Bean
					UserDetailsService userDetailsService() {
						return new InMemoryUserDetailsManager(
								User.withUsername("user").password("{noop}password").roles("USER").build());
					}

				}
				""";

		private static final String PARENT_SERVICE = """
				package com.example.app.infra;

				import org.springframework.stereotype.Component;

				@Component
				public class ParentAuditedService {

					@ParentAudited
					public String label() {
						return "parent-bean";
					}

				}
				""";

		private static final String CHILD_CONFIG = """
				package com.example.app.config;

				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;

				import com.example.app.infra.ChildAudited;
				import com.example.app.infra.MarkingAdvisor;

				@Configuration(proxyBeanMethods = false)
				public class ChildAdvisorConfig {

					@Bean
					static MarkingAdvisor childAdvisor() {
						return new MarkingAdvisor(ChildAudited.class, "[child-advisor]");
					}

				}
				""";

		private static final String CHILD_SERVICE = """
				package com.example.app.service;

				import org.springframework.stereotype.Service;

				import com.example.app.infra.ChildAudited;
				import com.example.app.infra.ParentAudited;

				@Service
				public class ChildAuditedService {

					@ParentAudited
					public String withParentAdvisor() {
						return "child-bean";
					}

					@ChildAudited
					public String withChildAdvisor() {
						return "child-bean";
					}

				}
				""";

		private static final String CONTROLLER = """
				package com.example.app.web;

				import org.springframework.security.access.prepost.PreAuthorize;
				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.infra.ParentAuditedService;
				import com.example.app.service.ChildAuditedService;

				@RestController
				public class AdvisorController {

					private final ParentAuditedService parentService;

					private final ChildAuditedService childService;

					public AdvisorController(ParentAuditedService parentService, ChildAuditedService childService) {
						this.parentService = parentService;
						this.childService = childService;
					}

					@GetMapping("/parent-bean")
					public String parentBean() {
						return this.parentService.label();
					}

					@GetMapping("/child-bean/parent-advisor")
					public String childBeanWithParentAdvisor() {
						return this.childService.withParentAdvisor();
					}

					@GetMapping("/child-bean/child-advisor")
					public String childBeanWithChildAdvisor() {
						return this.childService.withChildAdvisor();
					}

					@GetMapping("/admin")
					@PreAuthorize("hasRole('ADMIN')")
					public String admin() {
						return "admin";
					}

				}
				""";

		private ConsumerApp app;

		@BeforeAll
		void start() {
			Map<String, String> sources = sources(PARENT_AUDITED, CHILD_AUDITED, MARKING_ADVISOR, PARENT_CONFIG,
					PARENT_SERVICE, CHILD_CONFIG, CHILD_SERVICE, CONTROLLER);
			sources.put(ConsumerApp.APPLICATION_CLASS, APPLICATION);
			this.app = ConsumerApp.start(sources,
					List.of("--reload.parent-packages=com.example.app.infra", "--spring.autoconfigure.exclude="));
		}

		@AfterAll
		void stop() throws Exception {
			this.app.close();
		}

		@Test
		void parentAdvisorAppliesToParentBean() {
			assertThat(this.app.getOk("/parent-bean")).isEqualTo("[parent-advisor]parent-bean");
		}

		@Test
		@KnownIssue("엔진이 누수를 막으려고 부모 context에 정의된 Advisor bean을 자식에 적용하지 않는다(설계상 제약). "
				+ "Advisor 클래스가 부모 패키지에 있어도 bean이 부모에 있으면 같다")
		void parentAdvisorAppliesToChildBean() {
			assertThat(this.app.getOk("/child-bean/parent-advisor")).isEqualTo("[parent-advisor]child-bean");
		}

		@Test
		@KnownIssue("@EnableMethodSecurity의 인터셉터는 부모의 Advisor bean이라 자식 컨트롤러의 @PreAuthorize가 "
				+ "적용되지 않고 권한 검사 없이 통과한다")
		void methodSecurityEnabledInParentAppliesToChildController() {
			assertThat(this.app.getAs("user", "/admin").statusCode()).isEqualTo(403);
		}

		/**
		 * 대응책: Advisor 클래스는 부모 패키지에 두고 bean은 자식 설정에서 등록한다. 매 세대 새 Advisor 인스턴스가
		 * 만들어져 세대와 함께 사라진다.
		 */
		@Test
		void advisorClassFromParentRegisteredInChildConfigurationAppliesToChildBean() {
			assertThat(this.app.getOk("/child-bean/child-advisor")).isEqualTo("[child-advisor]child-bean");
		}

		@Test
		void advisorRegisteredInChildConfigurationStillAppliesAfterReload() {
			this.app.reloadSuccessfully();

			assertThat(this.app.getOk("/child-bean/child-advisor")).isEqualTo("[child-advisor]child-bean");
		}

		@Test
		void advisorRegisteredInChildConfigurationDoesNotPinPreviousGenerations() throws InterruptedException {
			this.app.assertPreviousGenerationsCollected(() -> {
				this.app.getOk("/child-bean/child-advisor");
				this.app.getOk("/parent-bean");
			});
		}

		/**
		 * 대응책: 자식에 {@code @Component} Advisor를 둔다({@code @Bean} 메서드가 없으므로 위의 캐시 문제도 없다).
		 * 부모 패키지의 Advisor 클래스를 상속해도 된다.
		 */
		@Test
		void advisorRegisteredAsChildComponentAppliesWithoutPinningGenerations() throws Exception {
			Map<String, String> sources = sources(PARENT_AUDITED, CHILD_AUDITED, MARKING_ADVISOR, PARENT_CONFIG,
					PARENT_SERVICE, CHILD_SERVICE, CONTROLLER, """
							package com.example.app.config;

							import org.springframework.stereotype.Component;

							import com.example.app.infra.ChildAudited;
							import com.example.app.infra.MarkingAdvisor;

							@Component
							public class ChildMarkingAdvisor extends MarkingAdvisor {

								public ChildMarkingAdvisor() {
									super(ChildAudited.class, "[child-advisor]");
								}

							}
							""");
			sources.put(ConsumerApp.APPLICATION_CLASS, APPLICATION);
			try (ConsumerApp componentApp = ConsumerApp.start(sources,
					List.of("--reload.parent-packages=com.example.app.infra", "--spring.autoconfigure.exclude="))) {
				assertThat(componentApp.getOk("/child-bean/child-advisor")).isEqualTo("[child-advisor]child-bean");

				componentApp.assertPreviousGenerationsCollected(() -> {
					componentApp.getOk("/child-bean/child-advisor");
					componentApp.getOk("/parent-bean");
				});
			}
		}

		/**
		 * 자식 설정에서 등록한 Advisor는 부모 bean에 적용되지 않는다(부모 bean은 부모 context에서 이미 만들어졌다).
		 */
		@Test
		void advisorRegisteredInChildConfigurationDoesNotApplyToParentBean() {
			assertThat(this.app.getOk("/parent-bean")).doesNotContain("[child-advisor]");
		}

	}

}
