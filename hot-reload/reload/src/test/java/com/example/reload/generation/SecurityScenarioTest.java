package com.example.reload.generation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Security: 문서의 권장대로 보안 설정은 부모(애플리케이션 클래스)에 두고, 컨트롤러는 자식이다
 * (문서 1장 메서드 보안, 6장).
 * <p>
 * 부모 보안 설정: HTTP Basic, {@code @EnableMethodSecurity}, 경로 매칭은 {@link PathPatternRequestMatcher}.
 * 일반 앱에서 흔한 {@code requestMatchers(String)} 형태는 {@link #stringRequestMatchersWork()}가 따로 확인한다.
 */
@SpringBootTest(classes = SecurityScenarioTest.ParentApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		// src/test/resources/application.properties가 꺼 둔 보안 auto-configuration을 다시 켠다.
		properties = "spring.autoconfigure.exclude=")
class SecurityScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("security");

	private static final String CONTROLLER = """
			package com.example.app;

			import org.springframework.security.access.prepost.PreAuthorize;
			import org.springframework.security.core.annotation.AuthenticationPrincipal;
			import org.springframework.security.core.userdetails.UserDetails;
			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class SecuredController {

				@GetMapping("/public/hello")
				public String publicHello() {
					return "public";
				}

				@GetMapping("/private/hello")
				public String privateHello() {
					return "private";
				}

				@GetMapping("/private/me")
				public String me(@AuthenticationPrincipal UserDetails user) {
					return (user != null) ? user.getUsername() : "null";
				}

				@GetMapping("/private/admin")
				@PreAuthorize("hasRole('ADMIN')")
				public String admin() {
					return "admin";
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

	@BeforeEach
	void deployController() {
		deploy(Map.of("SecuredController", CONTROLLER));
	}

	@Test
	void publicEndpointIsAccessibleWithoutAuthentication() {
		assertThat(getOk("/public/hello")).isEqualTo("public");
	}

	@Test
	void protectedEndpointRequiresAuthentication() {
		assertThat(get("/private/hello").statusCode()).isEqualTo(401);
	}

	@Test
	void authenticatedUserCanAccessProtectedEndpoint() {
		HttpResponse<String> response = getAs("user", "/private/hello");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("private");
	}

	@Test
	void securityStillAppliesAfterReload() {
		reload();

		assertThat(get("/private/hello").statusCode()).isEqualTo(401);
		assertThat(getAs("user", "/private/hello").statusCode()).isEqualTo(200);
	}

	@Test
	void authenticationPrincipalIsResolvedInChildController() {
		HttpResponse<String> response = getAs("user", "/private/me");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("user");
	}

	/**
	 * {@code @AuthenticationPrincipal}을 처리하는 argument resolver는 Spring Security의 정적 애너테이션 스캐너를
	 * 쓴다. 스캐너 캐시에 자식 컨트롤러 메서드의 파라미터가 남으면 누수다.
	 */
	@Test
	void authenticationPrincipalResolutionDoesNotPinPreviousGenerations() throws InterruptedException {
		assertPreviousGenerationsCollected(() -> assertThat(getAs("user", "/private/me").body()).isEqualTo("user"));
	}

	/**
	 * 권한 없는 사용자가 {@code @PreAuthorize} 메서드를 호출하면 403이어야 한다. 통과하면 보안 구멍이다.
	 */
	@Test
	@KnownIssue("@EnableMethodSecurity의 인터셉터는 Advisor bean이라 엔진이 자식에 적용하지 않는다. "
			+ "권한 검사 없이 통과한다 (1장)")
	void preAuthorizeIsEnforcedOnChildController() {
		assertThat(getAs("user", "/private/admin").statusCode()).isEqualTo(403);
		assertThat(getAs("admin", "/private/admin").statusCode()).isEqualTo(200);
	}

	/**
	 * 이 테스트 클래스의 부모와 별도로 애플리케이션을 하나 더 띄운다(기동 실패 자체를 확인해야 하므로).
	 */
	@Test
	@KnownIssue("requestMatchers(String)은 mvcHandlerMappingIntrospector bean을 요구하는데 부모에는 MVC가 없어 "
			+ "애플리케이션이 뜨지 못한다 (6장)")
	void stringRequestMatchersWork() {
		SpringApplication application = new SpringApplication(StringMatcherApplication.class);
		try (ConfigurableApplicationContext context = application.run("--server.port=0",
				"--spring.autoconfigure.exclude=", "--reload.classpath=" + compiler.classesDirectory(),
				"--reload.base-packages=com.example.app", "--reload.trigger.mode=api")) {
			int port = ((WebServerApplicationContext) context).getWebServer().getPort();
			HttpResponse<String> response = send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/public/hello")).GET().build());
			assertThat(response.statusCode()).isEqualTo(200);
		}
	}

	private HttpResponse<String> getAs(String username, String path) {
		String credentials = Base64.getEncoder()
			.encodeToString((username + ":password").getBytes(StandardCharsets.UTF_8));
		return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Basic " + credentials).GET().build());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EnableMethodSecurity
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		/**
		 * {@code requestMatchers(String)}은 부모에서 기동이 실패하므로({@link #stringRequestMatchersWork()})
		 * MVC에 의존하지 않는 {@link PathPatternRequestMatcher}를 쓴다.
		 */
		@Bean
		SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
			return http
				.authorizeHttpRequests((requests) -> requests
					.requestMatchers(PathPatternRequestMatcher.withDefaults().matcher("/public/**"))
					.permitAll()
					.anyRequest()
					.authenticated())
				.httpBasic(Customizer.withDefaults())
				.csrf((csrf) -> csrf.disable())
				.build();
		}

		@Bean
		UserDetailsService userDetailsService() {
			return users();
		}

	}

	/**
	 * 일반 앱에서 가장 흔한 형태의 보안 설정({@code requestMatchers(String)}).
	 */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class StringMatcherApplication {

		@Bean
		SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
			return http
				.authorizeHttpRequests((requests) -> requests.requestMatchers("/public/**")
					.permitAll()
					.anyRequest()
					.authenticated())
				.httpBasic(Customizer.withDefaults())
				.build();
		}

		@Bean
		UserDetailsService userDetailsService() {
			return users();
		}

	}

	private static UserDetailsService users() {
		return new InMemoryUserDetailsManager(
				User.withUsername("user").password("{noop}password").roles("USER").build(),
				User.withUsername("admin").password("{noop}password").roles("USER", "ADMIN").build());
	}

}
