package com.example.reload.generation;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모 bean이 자식 객체를 받는 경우: {@code CacheManager}, {@code ObjectMapper}, {@code Validator} (문서 4장).
 * <p>
 * 부모는 {@code @EnableCaching}으로 Boot의 simple({@code ConcurrentMapCacheManager}) 캐시를 쓴다.
 */
@SpringBootTest(classes = SharedStateScenarioTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class SharedStateScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("shared-state");

	private static final String GREETING = """
			package com.example.app;

			public record Greeting(String text) {
			}
			""";

	private static final String GREETING_SERVICE = """
			package com.example.app;

			import org.springframework.cache.annotation.Cacheable;
			import org.springframework.stereotype.Service;

			import com.example.reload.fixture.Probe;

			@Service
			public class CachedGreetingService {

				private final Probe probe;

				public CachedGreetingService(Probe probe) {
					this.probe = probe;
				}

				@Cacheable("greetings")
				public Greeting greeting(String name) {
					this.probe.record("greeting-computed", name);
					return new Greeting("Hello, " + name);
				}

			}
			""";

	private static final String GREETING_CONTROLLER = """
			package com.example.app;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RequestParam;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class GreetingController {

				private final CachedGreetingService service;

				public GreetingController(CachedGreetingService service) {
					this.service = service;
				}

				@GetMapping("/greeting")
				public String greeting(@RequestParam("name") String name) {
					Greeting greeting = this.service.greeting(name);
					return greeting.text();
				}

			}
			""";

	@Autowired
	private CacheManager cacheManager;

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		registerReloadProperties(registry, compiler);
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	@BeforeEach
	void clearCaches() {
		this.cacheManager.getCacheNames().forEach((name) -> this.cacheManager.getCache(name).clear());
	}

	@Test
	void cacheableWorksInChild() {
		deploy(Map.of("Greeting", GREETING, "CachedGreetingService", GREETING_SERVICE, "GreetingController",
				GREETING_CONTROLLER));

		assertThat(getOk("/greeting?name=first")).isEqualTo("Hello, first");
		assertThat(getOk("/greeting?name=first")).isEqualTo("Hello, first");

		assertThat(this.probe.events("greeting-computed")).containsExactly("first");
	}

	/**
	 * 캐시는 부모 {@code CacheManager}에 있어 재로딩 뒤에도 남는다. 일반 앱의 재시작과 달리 캐시 값이 이전 세대
	 * 클래스의 인스턴스다.
	 */
	@Test
	@KnownIssue("부모 캐시에 남은 값이 이전 세대 클래스라 새 세대에서 ClassCastException (4장)")
	void cachedChildValueIsUsableAfterReload() {
		deploy(Map.of("Greeting", GREETING, "CachedGreetingService", GREETING_SERVICE, "GreetingController",
				GREETING_CONTROLLER));
		getOk("/greeting?name=reload");

		reload();

		assertThat(getOk("/greeting?name=reload")).isEqualTo("Hello, reload");
	}

	@Test
	@KnownIssue("부모 캐시에 남은 자식 인스턴스가 이전 세대 클래스로더를 붙잡는다 (4장)")
	void cachedChildValuesDoNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("Greeting", GREETING, "CachedGreetingService", GREETING_SERVICE, "GreetingController",
				GREETING_CONTROLLER));
		int[] counter = new int[1];

		assertPreviousGenerationsCollected(() -> getOk("/greeting?name=user" + (counter[0]++)));
	}

	/**
	 * 자식 코드가 {@code ObjectMapper}를 주입받으면 부모 인스턴스다. MVC 컨버터는 엔진이 복사본을 쓰지만 직접
	 * 쓰는 코드는 부모 인스턴스의 (역)직렬화기 캐시를 채운다.
	 */
	@Test
	@KnownIssue("부모 ObjectMapper의 (역)직렬화기 캐시에 자식 클래스가 남는다. GenerationCacheCleaner는 TypeFactory "
			+ "캐시만 비운다. 복사본을 쓰면 괜찮다(copiedObjectMapperInChildDoesNotPinPreviousGenerations) (4장)")
	void parentObjectMapperUsedByChildDoesNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("JsonController", """
				package com.example.app;

				import com.fasterxml.jackson.core.JsonProcessingException;
				import com.fasterxml.jackson.databind.ObjectMapper;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class JsonController {

					private final ObjectMapper objectMapper;

					public JsonController(ObjectMapper objectMapper) {
						this.objectMapper = objectMapper;
					}

					@GetMapping("/json")
					public String json() throws JsonProcessingException {
						String json = this.objectMapper.writeValueAsString(new Payload("value", 1));
						return this.objectMapper.readValue(json, Payload.class).name();
					}

					public record Payload(String name, int count) {
					}

				}
				"""));

		assertThat(getOk("/json")).isEqualTo("value");
		assertPreviousGenerationsCollected(() -> getOk("/json"));
	}

	/**
	 * 자식 {@code @Configuration}의 {@code @Bean} 메서드. 일반 앱에서 가장 흔한 bean 정의 방식이다.
	 */
	@Test
	void childConfigurationBeanMethodsDoNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("SupplierConfig", """
				package com.example.app;

				import java.util.function.Supplier;

				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;

				@Configuration(proxyBeanMethods = false)
				public class SupplierConfig {

					@Bean
					Supplier<String> greetingSupplier() {
						return () -> "hello";
					}

				}
				""", "SupplierController", SUPPLIER_CONTROLLER));

		assertThat(getOk("/supplied")).isEqualTo("hello");
		assertPreviousGenerationsCollected(() -> getOk("/supplied"));
	}

	/**
	 * {@code @Bean} 메서드 대신 {@code @Component} 클래스로 정의하면 이전 세대가 수거된다(위 테스트와의 대조군).
	 * 제네릭 인터페이스를 구현하지 않는 클래스여야 한다({@link #childBeanImplementingGenericInterfaceDoesNotPinPreviousGenerations}).
	 */
	@Test
	void childComponentBeansDoNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("GreetingProvider", """
				package com.example.app;

				import org.springframework.stereotype.Component;

				@Component
				public class GreetingProvider {

					public String get() {
						return "hello";
					}

				}
				""", "ProviderController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class ProviderController {

					private final GreetingProvider provider;

					public ProviderController(GreetingProvider provider) {
						this.provider = provider;
					}

					@GetMapping("/supplied")
					public String supplied() {
						return this.provider.get();
					}

				}
				"""));

		assertThat(getOk("/supplied")).isEqualTo("hello");
		assertPreviousGenerationsCollected(() -> getOk("/supplied"));
	}

	/**
	 * 자식 클래스가 제네릭 인터페이스({@code Supplier<String>}, {@code Converter<S, T>}, {@code ApplicationListener<E>}
	 * 등)를 구현하면 컴파일러가 브리지 메서드를 만든다.
	 */
	@Test
	void childBeanImplementingGenericInterfaceDoesNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("GreetingSupplier", """
				package com.example.app;

				import java.util.function.Supplier;

				import org.springframework.stereotype.Component;

				@Component
				public class GreetingSupplier implements Supplier<String> {

					@Override
					public String get() {
						return "hello";
					}

				}
				""", "SupplierController", SUPPLIER_CONTROLLER));

		assertThat(getOk("/supplied")).isEqualTo("hello");
		assertPreviousGenerationsCollected(() -> getOk("/supplied"));
	}

	private static final String SUPPLIER_CONTROLLER = """
			package com.example.app;

			import java.util.function.Supplier;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class SupplierController {

				private final Supplier<String> supplier;

				public SupplierController(Supplier<String> supplier) {
					this.supplier = supplier;
				}

				@GetMapping("/supplied")
				public String supplied() {
					return this.supplier.get();
				}

			}
			""";

	/**
	 * 대응책: 자식에서 {@code ObjectMapper#copy()}로 만든 복사본을 쓰면 캐시가 세대와 함께 사라진다.
	 */
	@Test
	void copiedObjectMapperInChildDoesNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("JsonController", """
				package com.example.app;

				import com.fasterxml.jackson.core.JsonProcessingException;
				import com.fasterxml.jackson.databind.ObjectMapper;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class JsonController {

					private final ObjectMapper objectMapper;

					public JsonController(ObjectMapper objectMapper) {
						this.objectMapper = objectMapper.copy();
					}

					@GetMapping("/json")
					public String json() throws JsonProcessingException {
						String json = this.objectMapper.writeValueAsString(new Payload("value", 1));
						return this.objectMapper.readValue(json, Payload.class).name();
					}

					public record Payload(String name, int count) {
					}

				}
				"""));

		assertThat(getOk("/json")).isEqualTo("value");
		assertPreviousGenerationsCollected(() -> getOk("/json"));
	}

	/**
	 * 자식 코드가 {@code jakarta.validation.Validator}를 주입받으면 부모의 {@code LocalValidatorFactoryBean}이다.
	 */
	@Test
	@KnownIssue("Hibernate Validator의 BeanMetaData 캐시(SOFT 참조)에 자식 클래스가 남는다. 메모리 압박이 오기 "
			+ "전까지 이전 세대가 수거되지 않는다 (4장)")
	void parentValidatorUsedByChildDoesNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("ValidationController", """
				package com.example.app;

				import jakarta.validation.Validator;
				import jakarta.validation.constraints.NotBlank;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class ValidationController {

					private final Validator validator;

					public ValidationController(Validator validator) {
						this.validator = validator;
					}

					@GetMapping("/violations")
					public int violations() {
						return this.validator.validate(new Form("")).size();
					}

					public record Form(@NotBlank String name) {
					}

				}
				"""));

		assertThat(getOk("/violations")).isEqualTo("1");
		assertPreviousGenerationsCollected(() -> getOk("/violations"));
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableCaching
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

	}

}
