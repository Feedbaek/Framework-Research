package com.example.reload.generation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import com.example.reload.fixture.GreetingService;
import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.NoOpTransactionManager;
import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.aop.proxy-target-class=false}: 부모와 자식이 같은 프록시 방식을 써야 한다. 다르면 구체 클래스로
 * 주입하는 코드가 개발(재로딩)과 운영({@code reload.enabled=false}) 중 한쪽에서만 동작한다 (문서 2장).
 */
@SpringBootTest(classes = ProxyModeScenarioTest.ParentApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "spring.aop.proxy-target-class=false")
class ProxyModeScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("proxy-mode");

	@Autowired
	private GreetingService parentTransactionalService;

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		registerReloadProperties(registry, compiler);
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	@Test
	void parentUsesInterfaceProxies() {
		assertThat(AopUtils.isJdkDynamicProxy(this.parentTransactionalService)).isTrue();
	}

	@Test
	@KnownIssue("ChildInfrastructureConfiguration이 @EnableTransactionManagement(proxyTargetClass = true)로 "
			+ "클래스 프록시를 강제한다 (2장)")
	void childUsesSameProxyModeAsParent() {
		deploy(Map.of("GreetingPort", """
				package com.example.app;

				public interface GreetingPort {

					String greet();

				}
				""", "TransactionalGreeting", """
				package com.example.app;

				import org.springframework.stereotype.Service;
				import org.springframework.transaction.annotation.Transactional;

				@Service
				public class TransactionalGreeting implements GreetingPort {

					@Override
					@Transactional
					public String greet() {
						return "hello";
					}

				}
				""", "ProxyController", """
				package com.example.app;

				import org.springframework.aop.support.AopUtils;
				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class ProxyController {

					private final GreetingPort port;

					public ProxyController(GreetingPort port) {
						this.port = port;
					}

					@GetMapping("/proxy")
					public String proxy() {
						return AopUtils.isJdkDynamicProxy(this.port) ? "jdk" : "cglib";
					}

				}
				"""));

		assertThat(getOk("/proxy")).isEqualTo("jdk");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new NoOpTransactionManager();
		}

		@Bean
		GreetingService parentTransactionalService() {
			return new TransactionalGreetingService();
		}

	}

	static class TransactionalGreetingService implements GreetingService {

		@Override
		@Transactional
		public String greet(String name) {
			return "Hello, " + name + "!";
		}

	}

}
