package com.example.reload.generation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.example.reload.fixture.CountingAspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 자식 세대에서 AOP가 동작할 때 세대 교체 후 이전 클래스로더가 수거되는지 확인하는 테스트의 공통 부분.
 * <p>
 * 자식 소스는 항상 {@code ChildAopConfig}(애플리케이션이 자식에 선언하는 설정)와 {@code AopController} 두
 * 클래스로 구성하고, 붙이는 애너테이션만 바꿔 경우를 나눈다. {@code ChildAopConfig}에 아무것도 붙이지 않으면
 * 엔진이 제공하는 자식 인프라({@link ChildInfrastructureConfiguration})만으로 동작한다. 부모에는
 * {@link CountingAspect}가 있다.
 */
abstract class AbstractAopLeakTest {

	static final String NONE = "";

	static final String AUTO_PROXY = "@org.springframework.context.annotation.EnableAspectJAutoProxy";

	static final String CHILD_TX_MANAGEMENT = "@org.springframework.transaction.annotation.EnableTransactionManagement";

	static final String TRANSACTIONAL = "@org.springframework.transaction.annotation.Transactional";

	private static final String CONFIG_TEMPLATE = """
			package com.example.app;

			@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
			%s
			public class ChildAopConfig {
			}
			""";

	private static final String CONTROLLER_TEMPLATE = """
			package com.example.app;

			import java.util.LinkedHashMap;
			import java.util.Map;

			import org.springframework.transaction.support.TransactionSynchronizationManager;
			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			@RestController
			%s
			public class AopController {

				@GetMapping("/aop")
				public Map<String, Object> aop() {
					ClassLoader loader = AopController.class.getClassLoader();
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("variant", "%s");
					body.put("txActive", TransactionSynchronizationManager.isActualTransactionActive());
					body.put("loader", Integer.toHexString(System.identityHashCode(loader)));
					return body;
				}

			}
			""";

	private static final int SWAPS = 20;

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@LocalServerPort
	private int port;

	@Autowired
	protected GenerationManager manager;

	@Autowired
	protected ConfigurableApplicationContext parentContext;

	@Autowired
	private CountingAspect parentAspect;

	protected abstract ControllerCompiler compiler();

	static void registerReloadProperties(DynamicPropertyRegistry registry, Path classesDirectory) {
		registry.add("reload.classpath", classesDirectory::toString);
		registry.add("reload.base-packages", () -> "com.example.app");
		registry.add("reload.poll-interval", () -> "300ms");
		registry.add("reload.quiet-period", () -> "100ms");
	}

	static void deploy(ControllerCompiler compiler, String variant, String configAnnotations,
			String controllerAnnotations) {
		compiler.deploySources(variant,
				Map.of("ChildAopConfig", CONFIG_TEMPLATE.formatted(configAnnotations), "AopController",
						CONTROLLER_TEMPLATE.formatted(controllerAnnotations, variant)));
	}

	static Path createWorkDirectory(String name) {
		try {
			Path parent = Files.createDirectories(Path.of("build", "reload-it").toAbsolutePath());
			return Files.createTempDirectory(parent, name + "-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * 경우 하나를 배포해 AOP가 실제로 적용됐는지 확인한 뒤, {@value #SWAPS}회 교체하고 이전 클래스로더가
	 * 모두 수거되는지 검사한다.
	 */
	void assertPreviousLoadersCollected(String variant, String configAnnotations, String controllerAnnotations,
			boolean expectTransaction) throws InterruptedException {
		deployAndAwait(variant, configAnnotations, controllerAnnotations);
		int aspectCallsBefore = this.parentAspect.invocations();
		Map<String, Object> body = getJson("/aop");
		assertThat(body).containsEntry("variant", variant);
		assertThat(this.parentAspect.invocations()).as("parent aspect applied to child controller")
			.isGreaterThan(aspectCallsBefore);
		assertThat(body).as("transaction active in child controller").containsEntry("txActive", expectTransaction);

		List<WeakReference<ClassLoader>> previousLoaders = new ArrayList<>();
		for (int i = 0; i < SWAPS; i++) {
			previousLoaders.add(useAndReplaceCurrentGeneration());
		}
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (countAlive(previousLoaders) > 0 && System.nanoTime() < deadline) {
			System.gc();
			Thread.sleep(100);
		}
		long alive = countAlive(previousLoaders);
		assertThat(alive)
			.as(() -> alive + " of " + SWAPS + " previous class loaders still reachable. Candidate caches:"
					+ LeakDiagnostics.describe(this.parentContext))
			.isZero();
	}

	/**
	 * 경우 하나를 배포하고 {@code /aop}를 한 번 호출한 뒤 현재 자식 context를 돌려준다(교체하지 않는다).
	 */
	org.springframework.context.ApplicationContext deployAndCall(String variant, String configAnnotations,
			String controllerAnnotations) {
		deployAndAwait(variant, configAnnotations, controllerAnnotations);
		assertThat(getJson("/aop")).containsEntry("variant", variant);
		return this.manager.current().context();
	}

	int parentAspectInvocations() {
		return this.parentAspect.invocations();
	}

	private WeakReference<ClassLoader> useAndReplaceCurrentGeneration() {
		WeakReference<ClassLoader> loader = new WeakReference<>(this.manager.current().classLoader());
		getJson("/aop");
		assertThat(this.manager.reload()).isTrue();
		return loader;
	}

	private void deployAndAwait(String variant, String configAnnotations, String controllerAnnotations) {
		int generationBefore = this.manager.currentGenerationId();
		deploy(compiler(), variant, configAnnotations, controllerAnnotations);
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (this.manager.currentGenerationId() <= generationBefore) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for generation serving " + variant);
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				fail("Interrupted");
			}
		}
	}

	private static long countAlive(List<WeakReference<ClassLoader>> references) {
		return references.stream().filter((reference) -> reference.get() != null).count();
	}

	private Map<String, Object> getJson(String path) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build();
			HttpResponse<String> response = this.httpClient.send(request, BodyHandlers.ofString());
			assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
			return this.objectMapper.readValue(response.body(), new TypeReference<>() {
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
