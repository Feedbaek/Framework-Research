package com.example.reload.generation;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.reload.fixture.Audited;
import com.example.reload.fixture.GreetingService;
import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.NoOpTransactionManager;
import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자식 bean에 대한 메서드 가로채기: {@code @Transactional}, {@code @Async}, {@code @Scheduled}, 메서드 검증,
 * 부모 {@code Advisor}, 자식 {@code @Aspect} (문서 1·2장).
 * <p>
 * 부모는 일반 Boot 앱처럼 애플리케이션 클래스에 {@code @EnableAsync}, {@code @EnableScheduling}을 선언하고,
 * 트랜잭션 매니저, 사용자 정의 {@code Advisor}({@link Audited}), 메서드 검증(validation starter)을 가진다.
 */
@SpringBootTest(classes = MethodInterceptionScenarioTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class MethodInterceptionScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("method-interception");

	private static final String ASYNC_WORKER = """
			package com.example.app;

			import org.springframework.scheduling.annotation.Async;
			import org.springframework.stereotype.Service;

			import com.example.reload.fixture.Probe;

			@Service
			public class AsyncWorker {

				private final Probe probe;

				public AsyncWorker(Probe probe) {
					this.probe = probe;
				}

				@Async
				public void work() {
					this.probe.record("async", Thread.currentThread().getName());
				}

			}
			""";

	private static final String ASYNC_CONTROLLER = """
			package com.example.app;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class AsyncController {

				private final AsyncWorker worker;

				public AsyncController(AsyncWorker worker) {
					this.worker = worker;
				}

				@GetMapping("/async")
				public String async() {
					this.worker.work();
					return Thread.currentThread().getName();
				}

			}
			""";

	private static final String TICKER = """
			package com.example.app;

			import org.springframework.scheduling.annotation.Scheduled;
			import org.springframework.stereotype.Component;

			import com.example.reload.fixture.Probe;

			@Component
			public class Ticker {

				private final Probe probe;

				public Ticker(Probe probe) {
					this.probe = probe;
				}

				@Scheduled(fixedDelay = 50)
				public void tick() {
					this.probe.record("tick", Probe.loaderId(getClass()));
				}

			}
			""";

	private static final String SLOW_CONTROLLER = """
			package com.example.app;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RequestParam;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class SlowController {

				@GetMapping("/slow")
				public String slow(@RequestParam("ms") long ms) throws InterruptedException {
					Thread.sleep(ms);
					return "done";
				}

			}
			""";

	private static final String CHILD_CONFIG_TEMPLATE = """
			package com.example.app;

			@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
			%s
			public class ChildConfig {
			}
			""";

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		registerReloadProperties(registry, compiler);
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	/**
	 * 엔진이 자식에 트랜잭션 인프라를 켜고 부모 트랜잭션 매니저를 쓴다.
	 */
	@Test
	void transactionalAppliesToChildService() {
		deploy(Map.of("TxService", """
				package com.example.app;

				import org.springframework.stereotype.Service;
				import org.springframework.transaction.annotation.Transactional;
				import org.springframework.transaction.support.TransactionSynchronizationManager;

				@Service
				public class TxService {

					@Transactional
					public boolean inTransaction() {
						return TransactionSynchronizationManager.isActualTransactionActive();
					}

				}
				""", "TxController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class TxController {

					private final TxService service;

					public TxController(TxService service) {
						this.service = service;
					}

					@GetMapping("/tx")
					public boolean tx() {
						return this.service.inTransaction();
					}

				}
				"""));

		assertThat(getOk("/tx")).isEqualTo("true");
	}

	@Test
	@KnownIssue("부모의 AsyncAnnotationBeanPostProcessor는 자식 bean을 처리하지 않아 @Async가 동기로 실행된다 (1장)")
	void asyncMethodRunsOnTaskExecutorThread() {
		deploy(Map.of("AsyncWorker", ASYNC_WORKER, "AsyncController", ASYNC_CONTROLLER));

		String callerThread = getOk("/async");

		await("async work recorded", () -> this.probe.count("async") == 1);
		assertThat(this.probe.events("async").get(0)).as("thread running @Async method").isNotEqualTo(callerThread);
	}

	@Test
	@KnownIssue("부모의 ScheduledAnnotationBeanPostProcessor는 자식 bean을 처리하지 않아 @Scheduled가 실행되지 않는다 (1장)")
	void scheduledMethodRuns() {
		deploy(Map.of("Ticker", TICKER));

		await("scheduled tick", Duration.ofSeconds(3), () -> this.probe.count("tick") > 0);
	}

	@Test
	void methodValidationRejectsInvalidArgument() {
		deploy(Map.of("NameService", """
				package com.example.app;

				import jakarta.validation.constraints.NotBlank;

				import org.springframework.stereotype.Service;
				import org.springframework.validation.annotation.Validated;

				@Service
				@Validated
				public class NameService {

					public String normalize(@NotBlank String name) {
						return name.trim();
					}

				}
				""", "NameController", """
				package com.example.app;

				import jakarta.validation.ConstraintViolationException;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RequestParam;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class NameController {

					private final NameService service;

					public NameController(NameService service) {
						this.service = service;
					}

					@GetMapping("/normalize")
					public String normalize(@RequestParam(name = "name", defaultValue = "") String name) {
						try {
							this.service.normalize(name);
							return "accepted";
						}
						catch (ConstraintViolationException ex) {
							return "rejected";
						}
					}

				}
				"""));

		assertThat(getOk("/normalize?name=%20")).isEqualTo("rejected");
	}

	@Test
	@KnownIssue("엔진이 누수를 막으려고 부모 Advisor bean을 자식에 적용하지 않는다(설계상 제약). "
			+ "@EnableMethodSecurity, @EnableRetry처럼 Advisor로 구현된 기능이 모두 해당 (1장)")
	void parentAdvisorAppliesToChildBean() {
		deploy(Map.of("AuditedService", """
				package com.example.app;

				import org.springframework.stereotype.Service;

				import com.example.reload.fixture.Audited;

				@Service
				public class AuditedService {

					@Audited
					public String audit() {
						return "ok";
					}

				}
				""", "AuditController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class AuditController {

					private final AuditedService service;

					public AuditController(AuditedService service) {
						this.service = service;
					}

					@GetMapping("/audit")
					public String audit() {
						return this.service.audit();
					}

				}
				"""));

		assertThat(getOk("/audit")).isEqualTo("ok");
		assertThat(this.probe.events("audited")).containsExactly("audit");
	}

	@Test
	@KnownIssue("부모 bean은 부모 context에서 한 번 만들어지므로 자식 @Aspect가 적용되지 않는다 (2장)")
	void childAspectAppliesToParentBean() {
		deploy(Map.of("GreetingAspect", """
				package com.example.app;

				import org.aspectj.lang.annotation.Aspect;
				import org.aspectj.lang.annotation.Before;
				import org.springframework.stereotype.Component;

				import com.example.reload.fixture.Probe;

				@Aspect
				@Component
				public class GreetingAspect {

					private final Probe probe;

					public GreetingAspect(Probe probe) {
						this.probe = probe;
					}

					@Before("execution(* com.example.reload.fixture.GreetingService.greet(..))")
					public void before() {
						this.probe.record("greeting-aspect", "before");
					}

				}
				""", "GreetingController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.reload.fixture.GreetingService;

				@RestController
				public class GreetingController {

					private final GreetingService greetingService;

					public GreetingController(GreetingService greetingService) {
						this.greetingService = greetingService;
					}

					@GetMapping("/greet")
					public String greet() {
						return this.greetingService.greet("child");
					}

				}
				"""));

		assertThat(getOk("/greet")).isEqualTo("Hello, child!");
		assertThat(this.probe.events("greeting-aspect")).containsExactly("before");
	}

	/**
	 * 문서의 대응책: 자식 패키지에 {@code @EnableAsync}를 직접 선언한다. 비동기로 실행되고, 교체 뒤 이전 세대가
	 * 수거돼야 한다.
	 */
	@Test
	void childEnabledAsyncRunsAsynchronously() {
		deploy(Map.of("ChildConfig", CHILD_CONFIG_TEMPLATE.formatted("@org.springframework.scheduling.annotation.EnableAsync"),
				"AsyncWorker", ASYNC_WORKER, "AsyncController", ASYNC_CONTROLLER));

		String callerThread = getOk("/async");

		await("async work recorded", () -> this.probe.count("async") == 1);
		assertThat(this.probe.events("async").get(0)).isNotEqualTo(callerThread);
	}

	@Test
	@KnownIssue("자식 @EnableAsync는 부모 applicationTaskExecutor를 쓰고, 풀 스레드가 만들어질 때 요청 스레드의 "
			+ "TCCL(세대 클래스로더)을 물려받아 이전 세대를 붙잡는다 (1장)")
	void childEnabledAsyncReleasesPreviousGenerations() throws InterruptedException {
		deploy(Map.of("ChildConfig", CHILD_CONFIG_TEMPLATE.formatted("@org.springframework.scheduling.annotation.EnableAsync"),
				"AsyncWorker", ASYNC_WORKER, "AsyncController", ASYNC_CONTROLLER));

		assertPreviousGenerationsCollected(() -> {
			int before = this.probe.count("async");
			getOk("/async");
			await("async work recorded", () -> this.probe.count("async") > before);
		});
	}

	/**
	 * 문서의 대응책: 자식 패키지에 {@code @EnableScheduling}을 직접 선언한다. 진행 중 요청이 없으면 이전 세대가
	 * 바로 닫히므로 이전 세대의 스케줄 작업도 멈춰야 한다.
	 */
	@Test
	void childEnabledSchedulingStopsWithPreviousGeneration() {
		deploy(Map.of("ChildConfig", CHILD_CONFIG_TEMPLATE.formatted("@org.springframework.scheduling.annotation.EnableScheduling"),
				"Ticker", TICKER));
		String firstLoader = Probe.loaderId(this.manager.current().classLoader());
		await("tick from first generation", () -> ticksFrom(firstLoader) > 0);

		reload();
		String secondLoader = Probe.loaderId(this.manager.current().classLoader());
		await("tick from second generation", () -> ticksFrom(secondLoader) > 0);
		long firstGenerationTicks = ticksFrom(firstLoader);
		sleep(Duration.ofMillis(300));

		assertThat(ticksFrom(firstLoader)).as("ticks from replaced generation").isEqualTo(firstGenerationTicks);
	}

	@Test
	@KnownIssue("자식 @EnableScheduling은 부모 taskScheduler를 쓰고, 스케줄러 스레드가 만들어질 때 세대 refresh 중인 "
			+ "스레드의 TCCL(세대 클래스로더)을 물려받아 그 세대를 붙잡는다 (1장)")
	void childEnabledSchedulingReleasesPreviousGenerations() throws InterruptedException {
		deploy(Map.of("ChildConfig", CHILD_CONFIG_TEMPLATE.formatted("@org.springframework.scheduling.annotation.EnableScheduling"),
				"Ticker", TICKER));

		assertPreviousGenerationsCollected(() -> {
			int before = this.probe.count("tick");
			await("tick", () -> this.probe.count("tick") > before);
		});
	}

	/**
	 * 일반 앱에는 스케줄 작업을 가진 bean이 하나뿐이다. 재로딩 중에도 두 세대가 동시에 같은 작업을 돌리면 안 된다.
	 */
	@Test
	@KnownIssue("이전 세대는 진행 중 요청이 끝날 때까지(최대 drain-timeout) 닫히지 않아 그동안 두 세대의 "
			+ "@Scheduled 작업이 함께 돈다 (1장)")
	void scheduledTasksDoNotOverlapWhilePreviousGenerationDrains() throws Exception {
		deploy(Map.of("ChildConfig", CHILD_CONFIG_TEMPLATE.formatted("@org.springframework.scheduling.annotation.EnableScheduling"),
				"Ticker", TICKER, "SlowController", SLOW_CONTROLLER));
		String firstLoader = Probe.loaderId(this.manager.current().classLoader());
		await("tick from first generation", () -> ticksFrom(firstLoader) > 0);
		Generation first = this.manager.current();
		CompletableFuture<HttpResponse<String>> slow = this.http
			.sendAsync(HttpRequest.newBuilder(uri("/slow?ms=2000")).build(), BodyHandlers.ofString());
		await("slow request in flight", () -> first.inFlight() == 1);

		reload();
		String secondLoader = Probe.loaderId(this.manager.current().classLoader());
		await("tick from second generation", () -> ticksFrom(secondLoader) > 0);
		long firstGenerationTicks = ticksFrom(firstLoader);
		sleep(Duration.ofMillis(500));
		long ticksWhileDraining = ticksFrom(firstLoader) - firstGenerationTicks;
		slow.get(10, TimeUnit.SECONDS);

		assertThat(ticksWhileDraining).as("ticks from the draining generation after the new one started").isZero();
	}

	private long ticksFrom(String loaderId) {
		List<String> ticks = this.probe.events("tick");
		return ticks.stream().filter(loaderId::equals).count();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableAsync
	@EnableScheduling
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		@Bean
		GreetingService greetingService() {
			return (name) -> "Hello, " + name + "!";
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new NoOpTransactionManager();
		}

		@Bean
		static Advisor auditAdvisor(org.springframework.beans.factory.ObjectProvider<Probe> probe) {
			MethodInterceptor interceptor = (invocation) -> {
				probe.getObject().record("audited", invocation.getMethod().getName());
				return invocation.proceed();
			};
			return new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(Audited.class),
					interceptor);
		}

	}

}
