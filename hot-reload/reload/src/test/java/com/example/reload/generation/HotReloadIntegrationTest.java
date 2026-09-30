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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.example.reload.fixture.GreetingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(classes = TestHostApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT)
class HotReloadIntegrationTest {

	private static final Duration SWAP_TIMEOUT = Duration.ofSeconds(10);

	private static final Path WORK_DIRECTORY = createWorkDirectory();

	private static final Path CLASSES_DIRECTORY = WORK_DIRECTORY.resolve("classes");

	private static final ControllerCompiler compiler = new ControllerCompiler(WORK_DIRECTORY, CLASSES_DIRECTORY);

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@LocalServerPort
	private int port;

	@Autowired
	private GenerationManager manager;

	@Autowired
	private ServletWebServerApplicationContext parentContext;

	@Autowired
	private GreetingService greetingService;

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		// 부모가 뜨기 전에 첫 세대가 로드할 v1을 준비한다.
		compiler.deploy("v1");
		registry.add("reload.classpath", CLASSES_DIRECTORY::toString);
		registry.add("reload.base-packages", () -> "com.example.app");
		registry.add("reload.poll-interval", () -> "300ms");
		registry.add("reload.quiet-period", () -> "100ms");
		registry.add("reload.drain-timeout", () -> "30s");
	}

	@Test
	void replacesGenerationWithoutRestartingTomcat() {
		deployAndAwait("v1");
		assertThat(getJson("/hello")).containsEntry("message", "v1");
		WebServer webServer = this.parentContext.getWebServer();
		int portBefore = webServer.getPort();

		deployAndAwait("v2");

		assertThat(getJson("/hello")).containsEntry("message", "v2");
		assertThat(this.parentContext.getWebServer()).isSameAs(webServer);
		assertThat(webServer.getPort()).isEqualTo(portBefore).isEqualTo(this.port);
		assertThat(((TomcatWebServer) webServer).getTomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
	}

	@Test
	void reloadApiIsNotRegisteredInWatchMode() throws Exception {
		int generation = this.manager.currentGenerationId();
		HttpResponse<String> response = this.httpClient.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/_reload"))
					.POST(HttpRequest.BodyPublishers.noBody())
					.build(),
				BodyHandlers.ofString());

		// 기본(watch) 모드에서는 API servlet이 없어 요청이 자식 세대로 가고, 매핑이 없으니 404다.
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(this.manager.currentGenerationId()).isEqualTo(generation);
	}

	@Test
	void parentHasNoMvcInfrastructure() {
		// 소비자가 exclude하지 않아도 엔진의 import filter가 부모의 MVC auto-configuration을 걸러 낸다.
		assertThat(this.parentContext.getBeanNamesForType(DispatcherServlet.class)).isEmpty();
		assertThat(this.parentContext.getBeanNamesForType(RequestMappingHandlerMapping.class)).isEmpty();
		assertThat(this.parentContext.getBeanNamesForType(GenerationManager.class)).hasSize(1);
	}

	@Test
	void sharesParentBeansAcrossGenerations() {
		deployAndAwait("v1");
		Map<String, Object> first = getJson("/hello");

		deployAndAwait("v2");
		Map<String, Object> second = getJson("/hello");

		String parentBeanIdentity = Integer.toHexString(System.identityHashCode(this.greetingService));
		assertThat(first.get("sharedBean")).isEqualTo(parentBeanIdentity);
		assertThat(second.get("sharedBean")).isEqualTo(parentBeanIdentity);
		assertThat(second.get("loader")).isNotEqualTo(first.get("loader"));
	}

	@Test
	void keepsCurrentGenerationWhenNewGenerationFailsToStart() {
		deployAndAwait("v2");
		int generationBefore = this.manager.currentGenerationId();
		int failuresBefore = this.manager.failedReloads();

		compiler.deployBroken("v3");
		await("failed reload of v3", () -> this.manager.failedReloads() > failuresBefore);

		assertThat(this.manager.currentGenerationId()).isEqualTo(generationBefore);
		assertThat(getJson("/hello")).containsEntry("message", "v2");
	}

	@Test
	void drainsInFlightRequestsBeforeClosingPreviousGeneration() throws Exception {
		deployAndAwait("v1");
		Generation previous = this.manager.current();
		AnnotationConfigWebApplicationContext previousContext = previous.context();
		CompletableFuture<HttpResponse<String>> slowRequest = this.httpClient
			.sendAsync(request("/slow?ms=3000"), BodyHandlers.ofString());
		await("slow request in flight", () -> previous.inFlight() == 1);

		deployAndAwait("v2");

		// 교체는 끝났지만 이전 세대는 요청이 끝날 때까지 살아 있다.
		assertThat(previous.isRetired()).isTrue();
		assertThat(previous.isDisposed()).isFalse();
		assertThat(previousContext.isActive()).isTrue();
		assertThat(getJson("/hello")).containsEntry("message", "v2");

		HttpResponse<String> response = slowRequest.get(10, TimeUnit.SECONDS);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(parse(response.body())).containsEntry("message", "v1");
		await("previous generation disposed", previous::isDisposed);
		assertThat(previousContext.isActive()).isFalse();
	}

	@Test
	void previousClassLoadersAreCollectedAfterRepeatedSwaps() throws InterruptedException {
		deployAndAwait("v1");
		List<WeakReference<ClassLoader>> previousLoaders = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			previousLoaders.add(useAndReplaceCurrentGeneration());
		}
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (countAlive(previousLoaders) > 0 && System.nanoTime() < deadline) {
			System.gc();
			Thread.sleep(100);
		}
		assertThat(countAlive(previousLoaders)).as("previous class loaders still reachable").isZero();
	}

	/**
	 * 현재 세대로 요청을 한 번 처리한 뒤 교체하고, 교체된 세대의 클래스로더를 약한 참조로 돌려준다.
	 * 별도 메서드로 둬서 호출한 쪽 스택에 강한 참조가 남지 않게 한다.
	 */
	private WeakReference<ClassLoader> useAndReplaceCurrentGeneration() {
		WeakReference<ClassLoader> loader = new WeakReference<>(this.manager.current().classLoader());
		assertThat(getJson("/hello")).containsEntry("message", "v1");
		assertThat(this.manager.reload()).isTrue();
		return loader;
	}

	private static long countAlive(List<WeakReference<ClassLoader>> references) {
		return references.stream().filter((reference) -> reference.get() != null).count();
	}

	private void deployAndAwait(String version) {
		int generationBefore = this.manager.currentGenerationId();
		compiler.deploy(version);
		// 동일한 바이트의 재컴파일은 더 이상 파일 변경으로 취급하지 않는다.
		assertThat(this.manager.reload()).isTrue();
		await("generation after " + generationBefore + " serving " + version,
				() -> this.manager.currentGenerationId() > generationBefore);
	}

	private static void await(String description, BooleanSupplier condition) {
		long deadline = System.nanoTime() + SWAP_TIMEOUT.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + description);
			}
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				fail("Interrupted waiting for " + description);
			}
		}
	}

	private Map<String, Object> getJson(String path) {
		try {
			HttpResponse<String> response = this.httpClient.send(request(path), BodyHandlers.ofString());
			assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
			return parse(response.body());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private HttpRequest request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).GET().build();
	}

	private Map<String, Object> parse(String json) {
		try {
			return this.objectMapper.readValue(json, new TypeReference<>() {
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Path createWorkDirectory() {
		try {
			// java.io.tmpdir 대신 모듈 build 디렉터리 아래를 쓴다(경로에 비 ASCII 문자가 들어가지 않도록).
			Path parent = Files.createDirectories(Path.of("build", "reload-it").toAbsolutePath());
			return Files.createTempDirectory(parent, "run-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
