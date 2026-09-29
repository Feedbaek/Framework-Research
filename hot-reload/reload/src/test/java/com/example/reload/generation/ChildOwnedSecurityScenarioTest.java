package com.example.reload.generation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보안 설정 클래스를 애플리케이션 기본 패키지(= 자식 소유)에 둔 경우. 일반 앱에서 가장 흔한 배치다 (문서 6장).
 */
@SpringBootTest(classes = ChildOwnedSecurityScenarioTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT, properties = "spring.autoconfigure.exclude=")
class ChildOwnedSecurityScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("child-security");

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		registerReloadProperties(registry, compiler);
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	@Test
	@KnownIssue("자식의 SecurityFilterChain은 서블릿 필터에 연결되지 않고, 부모에는 Boot 기본 체인(모든 요청 인증)이 "
			+ "적용된다 (6장)")
	void securityConfigurationInApplicationPackageIsApplied() {
		deploy(Map.of("SecurityConfig", """
				package com.example.app;

				import org.springframework.context.annotation.Bean;
				import org.springframework.context.annotation.Configuration;
				import org.springframework.security.config.annotation.web.builders.HttpSecurity;
				import org.springframework.security.web.SecurityFilterChain;

				@Configuration(proxyBeanMethods = false)
				public class SecurityConfig {

					@Bean
					SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
						return http.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll()).build();
					}

				}
				""", "OpenController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class OpenController {

					@GetMapping("/open")
					public String open() {
						return "open";
					}

				}
				"""));

		assertThat(getOk("/open")).isEqualTo("open");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

	}

}
