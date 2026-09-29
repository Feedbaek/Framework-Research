package com.example.reload.generation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.example.reload.generation.ConsumerApp.sources;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 배치 규칙(인프라 설정은 부모, Advisor·BeanPostProcessor 기반 {@code @Enable*}만 자식) 검사가 실제 소비자
 * 앱 배치에서 동작하는지 확인한다. 부모 패키지는 {@code com.example.app.infra}다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfigurationPlacementScenarioTest {

	private static final List<String> ARGUMENTS = List.of("--reload.parent-packages=com.example.app.infra");

	private static final String RULE = "Configuration placement rule";

	private static final String INFRA_CONFIG = """
			package com.example.app.infra;

			import com.fasterxml.jackson.databind.PropertyNamingStrategies;

			import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
			import org.springframework.context.annotation.Bean;
			import org.springframework.context.annotation.Configuration;
			import org.springframework.transaction.PlatformTransactionManager;

			import com.example.reload.fixture.NoOpTransactionManager;

			@Configuration(proxyBeanMethods = false)
			public class InfraConfig {

				@Bean
				PlatformTransactionManager transactionManager() {
					return new NoOpTransactionManager();
				}

				@Bean
				Jackson2ObjectMapperBuilderCustomizer snakeCase() {
					return (builder) -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
				}

			}
			""";

	private static final String CHILD_ASYNC_CONFIG = """
			package com.example.app.config;

			import org.springframework.context.annotation.Configuration;
			import org.springframework.scheduling.annotation.EnableAsync;

			@Configuration(proxyBeanMethods = false)
			@EnableAsync
			public class AppConfig {
			}
			""";

	private static final String CHILD_VIOLATING_CONFIG = """
			package com.example.app.config;

			import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
			import org.springframework.context.annotation.Bean;
			import org.springframework.context.annotation.Configuration;
			import org.springframework.scheduling.annotation.EnableAsync;
			import org.springframework.transaction.annotation.EnableTransactionManagement;

			@Configuration(proxyBeanMethods = false)
			@EnableAsync
			@EnableTransactionManagement
			public class AppConfig {

				@Bean
				Jackson2ObjectMapperBuilderCustomizer indent() {
					return (builder) -> builder.indentOutput(true);
				}

			}
			""";

	private static final String CHILD_FILTER = """
			package com.example.app.web;

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
					chain.doFilter(request, response);
				}

			}
			""";

	private static final String CONTROLLER = """
			package com.example.app.web;

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

	private static final String APPLICATION_WITH_ASYNC = """
			package com.example.app;

			import org.springframework.boot.autoconfigure.SpringBootApplication;
			import org.springframework.scheduling.annotation.EnableAsync;

			@SpringBootApplication
			@EnableAsync
			public class DemoApplication {
			}
			""";

	@Test
	void compliantLayoutLogsNoPlacementWarning(CapturedOutput output) throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(INFRA_CONFIG, CHILD_ASYNC_CONFIG, CONTROLLER), ARGUMENTS)) {
			assertThat(app.getOk("/ping")).isEqualTo("pong");
			app.reloadSuccessfully();

			assertThat(output.getOut()).doesNotContain(RULE);
		}
	}

	@Test
	void childViolationsAreReportedWithReasons(CapturedOutput output) throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(INFRA_CONFIG, CHILD_VIOLATING_CONFIG, CHILD_FILTER, CONTROLLER),
				ARGUMENTS)) {
			assertThat(output.getOut()).contains(RULE + ": infrastructure configuration belongs in the parent")
				.contains("Violations in generation 1:")
				.contains("com.example.app.config.AppConfig @EnableTransactionManagement: define the "
						+ "TransactionManager in the parent")
				.contains("com.example.app.config.AppConfig#indent() [Jackson2ObjectMapperBuilderCustomizer] is not "
						+ "applied to the parent ObjectMapper")
				.contains("com.example.app.web.HeaderFilter [Filter] is not registered with the servlet container")
				.doesNotContain("AppConfig @EnableAsync");
		}
	}

	@Test
	void parentFeaturesThatDoNotReachGenerationsAreReported(CapturedOutput output) throws Exception {
		Map<String, String> sources = sources(INFRA_CONFIG, CONTROLLER);
		sources.put(ConsumerApp.APPLICATION_CLASS, APPLICATION_WITH_ASYNC);
		try (ConsumerApp app = ConsumerApp.start(sources, ARGUMENTS)) {
			assertThat(output.getOut()).contains("Parent configuration that does not apply to generation beans:")
				.contains("@EnableAsync is declared in the parent")
				// Boot가 부모에 만드는 트랜잭션 Advisor는 엔진이 자식에 다시 켜므로 보고하지 않는다.
				.doesNotContain("TransactionAttributeSourceAdvisor");
		}
	}

	@Test
	void childViolationsAreLoggedOnlyWhenTheyChange(CapturedOutput output) throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(INFRA_CONFIG, CHILD_VIOLATING_CONFIG, CONTROLLER),
				ARGUMENTS)) {
			app.reloadSuccessfully();
			app.reloadSuccessfully();

			assertThat(occurrences(output.getOut(), "Violations in generation")).isOne();

			app.update(sources(CHILD_ASYNC_CONFIG));
			app.reloadSuccessfully();

			assertThat(output.getOut()).contains("Configuration placement violations resolved in generation 4");
		}
	}

	@Test
	void placementCheckCanBeDisabled(CapturedOutput output) throws Exception {
		Map<String, String> sources = sources(INFRA_CONFIG, CHILD_VIOLATING_CONFIG, CHILD_FILTER, CONTROLLER);
		sources.put(ConsumerApp.APPLICATION_CLASS, APPLICATION_WITH_ASYNC);
		try (ConsumerApp app = ConsumerApp.start(sources,
				List.of("--reload.parent-packages=com.example.app.infra", "--reload.placement-check.enabled=false"))) {
			assertThat(app.getOk("/ping")).isEqualTo("pong");

			assertThat(output.getOut()).doesNotContain(RULE);
		}
	}

	private static int occurrences(String text, String fragment) {
		int count = 0;
		for (int index = text.indexOf(fragment); index != -1; index = text.indexOf(fragment, index + 1)) {
			count++;
		}
		return count;
	}

}
