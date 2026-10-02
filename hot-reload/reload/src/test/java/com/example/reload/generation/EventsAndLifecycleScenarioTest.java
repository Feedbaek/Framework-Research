package com.example.reload.generation;

import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.reload.fixture.DomainEvent;
import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.Probe;
import com.example.reload.fixture.SimpleDomainEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이벤트 전파와 세대 라이프사이클: 부모 이벤트 수신, runner, 자식 이벤트의 부모 전달, {@code @PostConstruct}에서
 * 부모 bean(MeterRegistry)에 등록하는 경우 (문서 5장).
 */
@SpringBootTest(classes = EventsAndLifecycleScenarioTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class EventsAndLifecycleScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("events");

	private static final String CHILD_EVENT = """
			package com.example.app;

			import com.example.reload.fixture.DomainEvent;

			public record ChildEvent(String name) implements DomainEvent {
			}
			""";

	private static final String PUBLISHING_CONTROLLER = """
			package com.example.app;

			import org.springframework.context.ApplicationEventPublisher;
			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class PublishingController {

				private final ApplicationEventPublisher publisher;

				public PublishingController(ApplicationEventPublisher publisher) {
					this.publisher = publisher;
				}

				@GetMapping("/publish")
				public String publish() {
					this.publisher.publishEvent(new ChildEvent("from-child"));
					// ApplicationEvent가 아닌 객체는 PayloadApplicationEvent로 감싸져 발행된다.
					this.publisher.publishEvent(new ChildPayload("payload"));
					return "published";
				}

				public record ChildPayload(String value) {
				}

			}
			""";

	private static final String GAUGE_BINDER = """
			package com.example.app;

			import io.micrometer.core.instrument.Gauge;
			import io.micrometer.core.instrument.MeterRegistry;
			import jakarta.annotation.PostConstruct;

			import org.springframework.stereotype.Component;

			@Component
			public class GenerationGauge {

				private final MeterRegistry registry;

				private final double value = System.identityHashCode(GenerationGauge.class.getClassLoader());

				public GenerationGauge(MeterRegistry registry) {
					this.registry = registry;
				}

				@PostConstruct
				void register() {
					Gauge.builder("child.generation", this, GenerationGauge::value).register(this.registry);
				}

				public double value() {
					return this.value;
				}

			}
			""";

	@Autowired
	private MeterRegistry meterRegistry;

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		registerReloadProperties(registry, compiler);
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	/**
	 * 일반 앱에서는 부모 소유 서비스가 발행한 도메인 이벤트를 같은 context의 리스너가 받는다.
	 */
	@Test
	@KnownIssue("이벤트는 자식에서 부모로만 전파되므로 부모가 발행한 이벤트를 자식 @EventListener가 받지 못한다 (5장)")
	void childListenerReceivesEventsPublishedByParent() {
		deploy(Map.of("DomainEventListener", """
				package com.example.app;

				import org.springframework.context.event.EventListener;
				import org.springframework.stereotype.Component;

				import com.example.reload.fixture.DomainEvent;
				import com.example.reload.fixture.Probe;

				@Component
				public class DomainEventListener {

					private final Probe probe;

					public DomainEventListener(Probe probe) {
						this.probe = probe;
					}

					@EventListener
					public void on(DomainEvent event) {
						this.probe.record("child-listener", event.name());
					}

				}
				"""));

		this.parentContext.publishEvent(new SimpleDomainEvent("from-parent"));

		assertThat(this.probe.events("child-listener")).containsExactly("from-parent");
	}

	/**
	 * 부모 이벤트 대신 자식 context 자신의 {@code ContextRefreshedEvent}로 세대 초기화를 할 수 있다.
	 */
	@Test
	void childListenerReceivesItsOwnContextRefreshedEvent() {
		deploy(Map.of("RefreshListener", """
				package com.example.app;

				import org.springframework.context.event.ContextRefreshedEvent;
				import org.springframework.context.event.EventListener;
				import org.springframework.stereotype.Component;

				import com.example.reload.fixture.Probe;

				@Component
				public class RefreshListener {

					private final Probe probe;

					public RefreshListener(Probe probe) {
						this.probe = probe;
					}

					@EventListener
					public void on(ContextRefreshedEvent event) {
						this.probe.record("refreshed", event.getApplicationContext().getDisplayName());
					}

				}
				"""));

		assertThat(this.probe.events("refreshed")).singleElement().asString().startsWith("Reload generation");
	}

	/**
	 * 세대의 기동·종료 이벤트는 부모로 전파되지 않는다. 부모 리스너(JEUS {@code JEUSFinalizer}, ProObject 등)는
	 * 출처를 확인하지 않고 {@code ContextClosedEvent}를 애플리케이션 종료로 처리한다. 세대 자신의 리스너는 계속 받는다.
	 */
	@Test
	void generationLifecycleEventsDoNotReachParentListeners() {
		deploy(Map.of("ClosedListener", """
				package com.example.app;

				import org.springframework.context.event.ContextClosedEvent;
				import org.springframework.context.event.EventListener;
				import org.springframework.stereotype.Component;

				import com.example.reload.fixture.Probe;

				@Component
				public class ClosedListener {

					private final Probe probe;

					public ClosedListener(Probe probe) {
						this.probe = probe;
					}

					@EventListener
					public void on(ContextClosedEvent event) {
						this.probe.record("child-closed", event.getApplicationContext().getDisplayName());
					}

				}
				"""));
		reload();

		assertThat(this.probe.events("child-closed")).singleElement().asString().startsWith("Reload generation");
		assertThat(this.probe.events("parent-lifecycle")).isEmpty();
	}

	@Test
	@KnownIssue("SpringApplication은 부모 context의 runner만 호출하므로 자식 ApplicationRunner가 실행되지 않는다 (5장)")
	void childApplicationRunnerRuns() {
		deploy(Map.of("StartupRunner", """
				package com.example.app;

				import org.springframework.boot.ApplicationArguments;
				import org.springframework.boot.ApplicationRunner;
				import org.springframework.stereotype.Component;

				import com.example.reload.fixture.Probe;

				@Component
				public class StartupRunner implements ApplicationRunner {

					private final Probe probe;

					public StartupRunner(Probe probe) {
						this.probe = probe;
					}

					@Override
					public void run(ApplicationArguments args) {
						this.probe.record("runner", "ran");
					}

				}
				"""));

		assertThat(this.probe.events("runner")).isNotEmpty();
	}

	@Test
	void childEventsReachParentListener() {
		deploy(Map.of("ChildEvent", CHILD_EVENT, "PublishingController", PUBLISHING_CONTROLLER));

		assertThat(getOk("/publish")).isEqualTo("published");

		assertThat(this.probe.events("parent-listener")).containsExactly("from-child");
	}

	/**
	 * 자식이 발행한 이벤트는 부모 multicaster를 거친다. 부모의 리스너 캐시에 자식 이벤트 타입이 남으면 누수다.
	 */
	@Test
	@KnownIssue("자식이 publishEvent(Object)로 발행한 이벤트가 부모 applicationEventMulticaster의 retrieverCache에 "
			+ "PayloadApplicationEvent<자식 타입> 키로 남아 모든 이전 세대를 붙잡는다 (5장)")
	void childEventsPublishedToParentDoNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("ChildEvent", CHILD_EVENT, "PublishingController", PUBLISHING_CONTROLLER));

		assertPreviousGenerationsCollected(() -> getOk("/publish"));
	}

	/**
	 * 세대마다 {@code @PostConstruct}에서 같은 이름의 gauge를 부모 {@link MeterRegistry}에 등록한다. 일반 앱에서
	 * 재시작하면 gauge는 새 인스턴스의 값을 보고한다.
	 */
	@Test
	@KnownIssue("MeterRegistry는 같은 이름의 meter를 처음 등록한 것으로 유지하므로 새 세대의 gauge가 무시된다 (5장)")
	void gaugeRegisteredByChildReportsCurrentGeneration() {
		deploy(Map.of("GenerationGauge", GAUGE_BINDER));
		reload();

		double expected = System.identityHashCode(this.manager.current().classLoader());
		assertThat(this.meterRegistry.get("child.generation").gauge().value()).isEqualTo(expected);
	}

	@Test
	@KnownIssue("MeterRegistry에 처음 등록된 gauge가 자식 클래스의 함수(메서드 참조)를 강하게 참조해서 그 세대를 "
			+ "붙잡는다 (5장)")
	void gaugeRegisteredByChildDoesNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("GenerationGauge", GAUGE_BINDER));

		assertPreviousGenerationsCollected(() -> {
		});
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		@Bean
		MeterRegistry meterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		ParentDomainEventListener parentDomainEventListener(Probe probe) {
			return new ParentDomainEventListener(probe);
		}

		@Bean
		ParentLifecycleListener parentLifecycleListener(Probe probe) {
			return new ParentLifecycleListener(probe);
		}

	}

	/**
	 * 부모 리스너. 다른 context(세대)에서 온 lifecycle 이벤트의 종류만 기록한다.
	 */
	static class ParentLifecycleListener {

		private final Probe probe;

		ParentLifecycleListener(Probe probe) {
			this.probe = probe;
		}

		@EventListener
		public void on(ApplicationContextEvent event) {
			if (event.getApplicationContext().getParent() != null) {
				this.probe.record("parent-lifecycle", event.getClass().getSimpleName());
			}
		}

	}

	/**
	 * 부모 리스너. 이벤트 이름(문자열)만 기록하고 이벤트 객체는 보관하지 않는다.
	 */
	static class ParentDomainEventListener {

		private final Probe probe;

		ParentDomainEventListener(Probe probe) {
			this.probe = probe;
		}

		@EventListener
		public void on(DomainEvent event) {
			this.probe.record("parent-listener", event.name());
		}

	}

}
