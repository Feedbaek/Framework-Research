package com.example.reload.generation;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.LibraryController;
import com.example.reload.fixture.Probe;
import com.example.reload.fixture.ReverseTextHttpMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 계층: 자식에는 Boot auto-configuration이 없고 서블릿 컨테이너 설정은 부모만 한다 (문서 6장).
 * <p>
 * 부모는 사용자 정의 {@code HttpMessageConverter} bean, 인터셉터를 추가하는 {@code WebMvcConfigurer} bean,
 * 라이브러리 컨트롤러 bean을 가진다.
 */
@SpringBootTest(classes = WebLayerScenarioTest.ParentApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "app.timeout=5s", "app.tags=a,b", "spring.jackson.default-property-inclusion=non_null" })
class WebLayerScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("web-layer");

	private static final String PING_CONTROLLER = """
			package com.example.app;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class PingController {

				@GetMapping("/ping")
				public String ping() {
					return "pong";
				}

			}
			""";

	private static final String PERSON_CONTROLLER = """
			package com.example.app;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class PersonController {

				@GetMapping("/person")
				public Person person() {
					return new Person("Minseok", null);
				}

				public record Person(String firstName, String nickname) {
				}

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

	@Test
	@KnownIssue("자식 bean인 Filter는 Tomcat에 등록되지 않는다. 서블릿 컨테이너 초기화는 부모만 한다 (6장)")
	void childFilterComponentIsApplied() {
		deploy(Map.of("PingController", PING_CONTROLLER, "HeaderFilter", """
				package com.example.app;

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
						response.setHeader("X-Child-Filter", "applied");
						chain.doFilter(request, response);
					}

				}
				"""));

		HttpResponse<String> response = get("/ping");

		assertThat(response.body()).isEqualTo("pong");
		assertThat(response.headers().firstValue("X-Child-Filter")).hasValue("applied");
	}

	@Test
	@KnownIssue("자식의 FilterRegistrationBean은 Tomcat에 등록되지 않는다 (6장)")
	void childFilterRegistrationBeanIsApplied() {
		deploy(Map.of("PingController", PING_CONTROLLER, "FilterConfig", """
				package com.example.app;

				import jakarta.servlet.Filter;
				import jakarta.servlet.http.HttpServletResponse;

				import org.springframework.boot.web.servlet.FilterRegistrationBean;
				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;

				@Configuration(proxyBeanMethods = false)
				public class FilterConfig {

					@Bean
					FilterRegistrationBean<Filter> headerFilter() {
						FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
							((HttpServletResponse) response).setHeader("X-Registered-Filter", "applied");
							chain.doFilter(request, response);
						});
						registration.addUrlPatterns("/*");
						return registration;
					}

				}
				"""));

		HttpResponse<String> response = get("/ping");

		assertThat(response.body()).isEqualTo("pong");
		assertThat(response.headers().firstValue("X-Registered-Filter")).hasValue("applied");
	}

	/**
	 * 부모의 {@code WebMvcConfigurer} bean(보통 라이브러리가 등록한다)도 자식 MVC 구성에 합쳐진다.
	 */
	@Test
	void parentWebMvcConfigurerIsAppliedToChildMvc() {
		deploy(Map.of("PingController", PING_CONTROLLER));

		HttpResponse<String> response = get("/ping");

		assertThat(response.body()).isEqualTo("pong");
		assertThat(response.headers().firstValue("X-Parent-Interceptor")).hasValue("applied");
	}

	@Test
	@KnownIssue("엔진이 등록한 서블릿에 multipart 설정이 없어 MultipartFile을 받지 못한다 (6장)")
	void multipartUploadIsAccepted() {
		deploy(Map.of("UploadController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.PostMapping;
				import org.springframework.web.bind.annotation.RequestParam;
				import org.springframework.web.bind.annotation.RestController;
				import org.springframework.web.multipart.MultipartFile;

				@RestController
				public class UploadController {

					@PostMapping("/upload")
					public String upload(@RequestParam("file") MultipartFile file) {
						return file.getOriginalFilename() + ":" + file.getSize();
					}

				}
				"""));
		String boundary = "reload-boundary";
		String body = "--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"hello.txt\"\r\n"
				+ "Content-Type: text/plain\r\n\r\n" + "hello" + "\r\n" + "--" + boundary + "--\r\n";

		HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/upload"))
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build());

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("hello.txt:5");
	}

	@Test
	@KnownIssue("자식 @EnableWebMvc에는 Boot의 classpath:/static 리소스 핸들러가 없다 (6장)")
	void staticResourceIsServed() {
		deploy(Map.of("PingController", PING_CONTROLLER), Map.of("static/hello.txt", "hello static"));

		assertThat(getOk("/hello.txt")).isEqualTo("hello static");
	}

	/**
	 * 일반 Boot 앱은 처리되지 않은 예외를 {@code BasicErrorController}의 JSON 오류 응답으로 돌려준다.
	 */
	@Test
	@KnownIssue("부모에서 ErrorMvcAutoConfiguration이 제외되고 자식에도 /error 처리가 없다 (6장)")
	void unhandledExceptionReturnsBootErrorJson() {
		deploy(Map.of("FailingController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class FailingController {

					@GetMapping("/boom")
					public String boom() {
						throw new IllegalStateException("boom");
					}

				}
				"""));

		HttpResponse<String> response = send(
				HttpRequest.newBuilder(uri("/boom")).header("Accept", "application/json").GET().build());

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(contentType) -> assertThat(contentType).startsWith("application/json"));
		assertThat(parse(response.body())).containsEntry("status", 500).containsEntry("path", "/boom");
	}

	@Test
	@KnownIssue("부모에서 ErrorMvcAutoConfiguration이 제외되고 자식에도 /error 처리가 없다 (6장)")
	void unmappedPathReturnsBootErrorJson() {
		deploy(Map.of("PingController", PING_CONTROLLER));

		HttpResponse<String> response = send(
				HttpRequest.newBuilder(uri("/no-such-path")).header("Accept", "application/json").GET().build());

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(parse(response.body())).containsEntry("status", 404).containsEntry("path", "/no-such-path");
	}

	@Test
	void childControllerAdviceHandlesException() {
		deploy(Map.of("BadRequestController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class BadRequestController {

					@GetMapping("/bad")
					public String bad() {
						throw new IllegalArgumentException("bad input");
					}

				}
				""", "ErrorAdvice", """
				package com.example.app;

				import org.springframework.http.HttpStatus;
				import org.springframework.web.bind.annotation.ExceptionHandler;
				import org.springframework.web.bind.annotation.ResponseStatus;
				import org.springframework.web.bind.annotation.RestControllerAdvice;

				@RestControllerAdvice
				public class ErrorAdvice {

					@ExceptionHandler(IllegalArgumentException.class)
					@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
					public String handle(IllegalArgumentException ex) {
						return "handled: " + ex.getMessage();
					}

				}
				"""));

		HttpResponse<String> response = get("/bad");

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).isEqualTo("handled: bad input");
	}

	@Test
	void requestBodyValidationReturnsBadRequest() {
		deploy(Map.of("FormController", """
				package com.example.app;

				import jakarta.validation.Valid;
				import jakarta.validation.constraints.NotBlank;

				import org.springframework.web.bind.annotation.PostMapping;
				import org.springframework.web.bind.annotation.RequestBody;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class FormController {

					@PostMapping("/forms")
					public String submit(@Valid @RequestBody Form form) {
						return form.name();
					}

					public record Form(@NotBlank String name) {
					}

				}
				"""));

		HttpResponse<String> invalid = postJson("/forms", "{\"name\":\"\"}");
		HttpResponse<String> valid = postJson("/forms", "{\"name\":\"kim\"}");

		assertThat(invalid.statusCode()).isEqualTo(400);
		assertThat(valid.statusCode()).isEqualTo(200);
		assertThat(valid.body()).isEqualTo("kim");
	}

	@Test
	@KnownIssue("ChildWebMvcConfig가 컨버터 목록을 고정해서 부모의 HttpMessageConverter bean이 쓰이지 않는다 (6장)")
	void parentHttpMessageConverterBeanIsUsed() {
		deploy(Map.of("ReverseController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class ReverseController {

					@GetMapping(path = "/reverse", produces = "text/x-reverse")
					public String reverse() {
						return "abc";
					}

				}
				"""));

		HttpResponse<String> response = send(
				HttpRequest.newBuilder(uri("/reverse")).header("Accept", "text/x-reverse").GET().build());

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("cba");
	}

	/**
	 * 엔진은 부모 {@code ObjectMapper}의 복사본으로 자식 MVC를 구성하므로 {@code spring.jackson.*} 설정이 적용된다.
	 */
	@Test
	void jacksonPropertiesApplyToChildMvc() {
		deploy(Map.of("PersonController", PERSON_CONTROLLER));

		assertThat(getJson("/person")).containsOnlyKeys("firstName");
	}

	@Test
	@KnownIssue("자식의 Jackson2ObjectMapperBuilderCustomizer는 부모 JacksonAutoConfiguration이 보지 못한다 (6장)")
	void childJacksonCustomizerIsApplied() {
		deploy(Map.of("PersonController", PERSON_CONTROLLER, "JacksonConfig", """
				package com.example.app;

				import com.fasterxml.jackson.databind.PropertyNamingStrategies;

				import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;

				@Configuration(proxyBeanMethods = false)
				public class JacksonConfig {

					@Bean
					Jackson2ObjectMapperBuilderCustomizer snakeCase() {
						return (builder) -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
					}

				}
				"""));

		assertThat(getJson("/person")).containsOnlyKeys("first_name");
	}

	@Test
	@KnownIssue("부모에 등록된 라이브러리 컨트롤러는 부모에 MVC가 없어 매핑되지 않는다 (6장)")
	void libraryControllerRegisteredInParentIsMapped() {
		deploy(Map.of("PingController", PING_CONTROLLER));

		assertThat(getOk("/library/info")).isEqualTo("library");
	}

	@Test
	void configurationPropertiesBindInChild() {
		deploy(Map.of("AppProperties", """
				package com.example.app;

				import java.time.Duration;
				import java.util.List;

				import org.springframework.boot.context.properties.ConfigurationProperties;

				@ConfigurationProperties("app")
				public record AppProperties(Duration timeout, List<String> tags) {
				}
				""", "PropertiesConfig", """
				package com.example.app;

				import org.springframework.boot.context.properties.EnableConfigurationProperties;
				import org.springframework.context.annotation.Configuration;

				@Configuration(proxyBeanMethods = false)
				@EnableConfigurationProperties(AppProperties.class)
				public class PropertiesConfig {
				}
				""", "PropertiesController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class PropertiesController {

					private final AppProperties properties;

					public PropertiesController(AppProperties properties) {
						this.properties = properties;
					}

					@GetMapping("/properties")
					public String properties() {
						return this.properties.timeout().toMillis() + "/" + this.properties.tags();
					}

				}
				"""));

		assertThat(getOk("/properties")).isEqualTo("5000/[a, b]");
	}

	/**
	 * 일반 Boot 앱에서는 {@code @Value}도 Boot 변환({@code 5s} → {@code Duration}, 쉼표 구분 → {@code List})을 쓴다.
	 */
	@Test
	@KnownIssue("자식 bean factory에는 Boot의 ApplicationConversionService가 없어 @Value(\"5s\") Duration 변환에 "
			+ "실패하고 새 세대가 뜨지 못한다 (6장)")
	void valueAnnotationUsesBootConversions() {
		deploy(Map.of("ValueController", """
				package com.example.app;

				import java.time.Duration;
				import java.util.List;

				import org.springframework.beans.factory.annotation.Value;
				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class ValueController {

					private final Duration timeout;

					private final List<String> tags;

					public ValueController(@Value("${app.timeout}") Duration timeout, @Value("${app.tags}") List<String> tags) {
						this.timeout = timeout;
						this.tags = tags;
					}

					@GetMapping("/values")
					public String values() {
						return this.timeout.toMillis() + "/" + this.tags;
					}

				}
				"""));

		assertThat(getOk("/values")).isEqualTo("5000/[a, b]");
	}

	/**
	 * 스트리밍 중인 비동기 요청은 재로딩이 일어나도 처음 세대에서 끝까지 처리돼야 한다.
	 */
	@Test
	void serverSentEventsContinueAcrossReload() throws Exception {
		deploy(Map.of("EventsController", """
				package com.example.app;

				import org.springframework.http.MediaType;
				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;
				import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

				import com.example.reload.fixture.Probe;

				@RestController
				public class EventsController {

					@GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
					public SseEmitter events() {
						SseEmitter emitter = new SseEmitter(10_000L);
						String loader = Probe.loaderId(getClass());
						Thread thread = new Thread(() -> {
							try {
								for (int i = 0; i < 5; i++) {
									Thread.sleep(200);
									emitter.send(loader + ":" + i);
								}
								emitter.complete();
							}
							catch (Exception ex) {
								emitter.completeWithError(ex);
							}
						});
						thread.start();
						return emitter;
					}

				}
				"""));
		String streamingLoader = Probe.loaderId(this.manager.current().classLoader());
		CompletableFuture<HttpResponse<String>> stream = this.http
			.sendAsync(HttpRequest.newBuilder(uri("/events")).GET().build(), BodyHandlers.ofString());
		Generation streaming = this.manager.current();
		await("stream in flight", () -> streaming.inFlight() == 1);

		reload();
		HttpResponse<String> response = stream.get(10, TimeUnit.SECONDS);

		assertThat(response.statusCode()).isEqualTo(200);
		List<String> events = response.body().lines().filter((line) -> line.startsWith("data:")).toList();
		assertThat(events).hasSize(5).allSatisfy((line) -> assertThat(line).startsWith("data:" + streamingLoader));
		await("streaming generation disposed", streaming::isDisposed);
	}

	private HttpResponse<String> postJson(String path, String json) {
		return send(HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(json))
			.build());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		@Bean
		ReverseTextHttpMessageConverter reverseTextHttpMessageConverter() {
			return new ReverseTextHttpMessageConverter();
		}

		@Bean
		LibraryController libraryController() {
			return new LibraryController();
		}

		@Bean
		WebMvcConfigurer parentInterceptorConfigurer() {
			return new WebMvcConfigurer() {

				@Override
				public void addInterceptors(InterceptorRegistry registry) {
					registry.addInterceptor(new HandlerInterceptor() {

						@Override
						public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
								Object handler) {
							response.setHeader("X-Parent-Interceptor", "applied");
							return true;
						}

					});
				}

			};
		}

	}

}
