package com.example.reload.generation;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import com.example.reload.fixture.KnownIssue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring-boot-starter-actuator}를 추가한 일반 웹 앱 (문서 6장).
 * <p>
 * actuator를 테스트 클래스패스에 두면 다른 모든 테스트 애플리케이션에도 켜지므로, Gradle이 넘겨 주는 jar
 * 목록({@code actuator.scenario.classpath})을 별도 클래스로더로 올려 이 테스트에서만 부모 애플리케이션을 띄운다.
 */
class ActuatorScenarioTest {

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

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	@KnownIssue("actuator의 WebMvcServletEndpointManagementContextConfiguration이 DispatcherServletPath bean을 "
			+ "요구하는데 엔진이 DispatcherServletAutoConfiguration을 제외해서 부모가 뜨지 못한다 (6장)")
	void applicationWithActuatorStartsAndServesChildControllers() {
		runWithActuator((port) -> {
			HttpResponse<String> response = get(port, "/ping");
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo("pong");
		});
	}

	@Test
	@KnownIssue("actuator 웹 엔드포인트는 부모의 DispatcherServlet을 전제로 하는데 부모에는 MVC가 없다 (6장)")
	void actuatorHealthEndpointIsServed() {
		runWithActuator((port) -> {
			HttpResponse<String> response = get(port, "/actuator/health");
			assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		});
	}

	private void runWithActuator(IntConsumer assertions) {
		ControllerCompiler compiler = AbstractReloadScenarioTest.createCompiler("actuator");
		compiler.replaceSources("actuator", Map.of("PingController", PING_CONTROLLER), Map.of());
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		try (URLClassLoader loader = new URLClassLoader(actuatorJars(), getClass().getClassLoader())) {
			thread.setContextClassLoader(loader);
			SpringApplication application = new SpringApplication(new DefaultResourceLoader(loader),
					ParentApplication.class);
			try (ConfigurableApplicationContext context = application.run("--server.port=0",
					"--reload.classpath=" + compiler.classesDirectory(), "--reload.base-packages=com.example.app",
					"--reload.trigger.mode=api",
					"--spring.autoconfigure.exclude="
							+ "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
							+ "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
							+ "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
							+ "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration")) {
				assertions.accept(((WebServerApplicationContext) context).getWebServer().getPort());
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			thread.setContextClassLoader(previous);
		}
	}

	private static URL[] actuatorJars() {
		String classpath = System.getProperty("actuator.scenario.classpath", "");
		assertThat(classpath).as("actuator.scenario.classpath (set by the Gradle test task)").isNotBlank();
		return Arrays.stream(classpath.split(File.pathSeparator))
			.filter((entry) -> !entry.isBlank())
			.map(ActuatorScenarioTest::toUrl)
			.toArray(URL[]::new);
	}

	private static URL toUrl(String path) {
		try {
			return Path.of(path).toUri().toURL();
		}
		catch (MalformedURLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private HttpResponse<String> get(int port, String path) {
		try {
			return this.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
					BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

	}

}
