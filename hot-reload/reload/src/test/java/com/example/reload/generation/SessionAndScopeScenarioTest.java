package com.example.reload.generation;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.CustomScopeConfigurer;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.reload.fixture.KnownIssue;
import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스코프 프록시와 HTTP 세션: 세션은 Tomcat이 들고 있어 세대보다 오래 산다 (문서 3장).
 */
@SpringBootTest(classes = SessionAndScopeScenarioTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionAndScopeScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("session");

	private static final String CART = """
			package com.example.app;

			import java.util.ArrayList;
			import java.util.List;

			import org.springframework.stereotype.Component;
			import org.springframework.web.context.annotation.SessionScope;

			@Component
			@SessionScope
			public class Cart {

				private final List<String> items = new ArrayList<>();

				public void add(String item) {
					this.items.add(item);
				}

				public List<String> items() {
					return this.items;
				}

			}
			""";

	private static final String CART_CONTROLLER = """
			package com.example.app;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RequestParam;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class CartController {

				private final Cart cart;

				public CartController(Cart cart) {
					this.cart = cart;
				}

				@GetMapping("/cart/add")
				public String add(@RequestParam("item") String item) {
					this.cart.add(item);
					return "added";
				}

				@GetMapping("/cart")
				public String items() {
					return String.join(",", this.cart.items());
				}

			}
			""";

	private static final String USER_INFO = """
			package com.example.app;

			public record UserInfo(String name) {
			}
			""";

	private static final String SESSION_CONTROLLER = """
			package com.example.app;

			import jakarta.servlet.http.HttpSession;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RequestParam;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			public class SessionController {

				@GetMapping("/login")
				public String login(@RequestParam("name") String name, HttpSession session) {
					session.setAttribute("user", new UserInfo(name));
					return "ok";
				}

				@GetMapping("/me")
				public String me(HttpSession session) {
					UserInfo user = (UserInfo) session.getAttribute("user");
					return (user != null) ? user.name() : "anonymous";
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
	void newSession() {
		this.cookies.getCookieStore().removeAll();
	}

	@Test
	void requestScopedBeanWorksInChild() {
		deploy(Map.of("RequestInfo", """
				package com.example.app;

				import org.springframework.stereotype.Component;
				import org.springframework.web.context.annotation.RequestScope;

				@Component
				@RequestScope
				public class RequestInfo {

					private final String id = Integer.toHexString(System.identityHashCode(this));

					public String id() {
						return this.id;
					}

				}
				""", "RequestInfoController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class RequestInfoController {

					private final RequestInfo info;

					public RequestInfoController(RequestInfo info) {
						this.info = info;
					}

					@GetMapping("/request-id")
					public String id() {
						return this.info.id();
					}

				}
				"""));

		assertThat(getOk("/request-id")).isNotEqualTo(getOk("/request-id"));
	}

	@Test
	void sessionScopedBeanWorksWithinGeneration() {
		deploy(Map.of("Cart", CART, "CartController", CART_CONTROLLER));

		getOk("/cart/add?item=apple");
		getOk("/cart/add?item=pear");

		assertThat(getOk("/cart")).isEqualTo("apple,pear");
	}

	/**
	 * 개발 중 코드를 고칠 때마다 로그인·장바구니 같은 세션 상태가 깨지면 안 된다.
	 */
	@Test
	@KnownIssue("세션의 scopedTarget 속성에 이전 세대 인스턴스가 남아 새 세대 프록시가 호출하면 실패한다 (3장)")
	void sessionScopedBeanSurvivesReload() {
		deploy(Map.of("Cart", CART, "CartController", CART_CONTROLLER));
		getOk("/cart/add?item=apple");

		reload();

		assertThat(getOk("/cart")).isEqualTo("apple");
	}

	@Test
	@KnownIssue("세션에 넣은 자식 객체는 이전 세대 클래스라 새 세대에서 캐스팅하면 ClassCastException (3장)")
	void sessionAttributeOfChildTypeSurvivesReload() {
		deploy(Map.of("UserInfo", USER_INFO, "SessionController", SESSION_CONTROLLER));
		getOk("/login?name=kim");

		reload();

		assertThat(getOk("/me")).isEqualTo("kim");
	}

	@Test
	@KnownIssue("세션이 자식 객체를 들고 있는 동안 그 세대의 클래스로더가 수거되지 않는다 (3장)")
	void sessionAttributesDoNotPinPreviousGenerations() throws InterruptedException {
		deploy(Map.of("UserInfo", USER_INFO, "SessionController", SESSION_CONTROLLER));

		assertPreviousGenerationsCollected(() -> {
			// 세대마다 새 세션을 만든다(서로 다른 사용자).
			this.cookies.getCookieStore().removeAll();
			getOk("/login?name=kim");
		});
	}

	/**
	 * 부모에 등록한 커스텀 스코프(Spring Cloud {@code refresh}, Spring Batch {@code step} 등)를 자식 bean이 쓴다.
	 */
	@Test
	@KnownIssue("스코프는 bean factory마다 등록되고 자식이 부모 스코프를 상속하지 않아 새 세대가 뜨지 못한다 (3장)")
	void customScopeRegisteredInParentIsAvailableInChild() {
		deploy(Map.of("ThreadScopedCounter", """
				package com.example.app;

				import org.springframework.context.annotation.Scope;
				import org.springframework.context.annotation.ScopedProxyMode;
				import org.springframework.stereotype.Component;

				@Component
				@Scope(scopeName = "thread", proxyMode = ScopedProxyMode.TARGET_CLASS)
				public class ThreadScopedCounter {

					private int count;

					public int increment() {
						return ++this.count;
					}

				}
				""", "CounterController", """
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class CounterController {

					private final ThreadScopedCounter counter;

					public CounterController(ThreadScopedCounter counter) {
						this.counter = counter;
					}

					@GetMapping("/count")
					public int count() {
						return this.counter.increment();
					}

				}
				"""));

		assertThat(Integer.parseInt(getOk("/count"))).isPositive();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		@Bean
		static CustomScopeConfigurer threadScope() {
			CustomScopeConfigurer configurer = new CustomScopeConfigurer();
			configurer.addScope("thread", new SimpleThreadScope());
			return configurer;
		}

	}

}
