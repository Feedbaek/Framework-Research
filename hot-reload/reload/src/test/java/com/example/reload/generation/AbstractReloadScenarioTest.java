package com.example.reload.generation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.CookieManager;
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
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.example.reload.fixture.Probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 일반 웹 애플리케이션 시나리오 테스트의 공통 부분({@code docs/reload-aop-proxy-risks.md} 참고).
 * <p>
 * 하위 클래스는 부모 애플리케이션(중첩 {@code @SpringBootConfiguration})에 시나리오에 필요한 bean과
 * {@code @Enable*}을 두고, 테스트마다 {@link #deploy(Map)}로 자식 소스({@code com.example.app})를 배포한다.
 * 재로딩은 {@code api} 모드로 두고 직접 호출하므로 파일 감시 타이밍에 흔들리지 않는다.
 * <p>
 * 각 테스트는 "일반 Spring Boot 앱이라면 기대하는 동작"을 검증한다. 현재 엔진에서 실패하는 테스트에는
 * {@link com.example.reload.fixture.KnownIssue}를 붙인다.
 */
abstract class AbstractReloadScenarioTest {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	static final int LEAK_CHECK_SWAPS = 10;

	protected final CookieManager cookies = new CookieManager();

	protected final HttpClient http = HttpClient.newBuilder().cookieHandler(this.cookies).build();

	protected final ObjectMapper objectMapper = new ObjectMapper();

	@LocalServerPort
	protected int port;

	@Autowired
	protected GenerationManager manager;

	@Autowired
	protected ConfigurableApplicationContext parentContext;

	@Autowired
	protected Probe probe;

	protected abstract ControllerCompiler compiler();

	@BeforeEach
	void resetProbe() {
		this.probe.clear();
	}

	static Path createWorkDirectory(String name) {
		try {
			// java.io.tmpdir 대신 모듈 build 디렉터리 아래를 쓴다(경로에 비 ASCII 문자가 들어가지 않도록).
			Path parent = Files.createDirectories(Path.of("build", "reload-it").toAbsolutePath());
			return Files.createTempDirectory(parent, name + "-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static ControllerCompiler createCompiler(String name) {
		Path workDirectory = createWorkDirectory(name);
		return new ControllerCompiler(workDirectory, workDirectory.resolve("classes"));
	}

	/**
	 * 첫 세대는 빈 클래스 디렉터리로 뜬다. 테스트가 {@link #deploy(Map)}로 소스를 배포하고 재로딩한다.
	 */
	static void registerReloadProperties(DynamicPropertyRegistry registry, ControllerCompiler compiler) {
		compiler.replaceSources("empty", Map.of(), Map.of());
		registry.add("reload.classpath", () -> compiler.classesDirectory().toString());
		registry.add("reload.base-packages", () -> "com.example.app");
		registry.add("reload.trigger.mode", () -> "api");
		registry.add("reload.drain-timeout", () -> "30s");
	}

	/**
	 * 클래스 디렉터리를 이 소스들로만 채우고 재로딩한다. 새 세대가 뜨지 못하면 실패한다.
	 */
	void deploy(Map<String, String> sources) {
		deploy(sources, Map.of());
	}

	void deploy(Map<String, String> sources, Map<String, String> resources) {
		compiler().replaceSources(getClass().getSimpleName(), sources, resources);
		reload();
	}

	/**
	 * 같은 클래스 파일로 새 세대를 만든다. 클래스 이름은 같지만 {@code Class}는 새로 로드된다.
	 */
	ReloadResult reload() {
		ReloadResult result = this.manager.reloadWithResult();
		assertThat(result.reloaded()).as("reload failed: %s", result.error()).isTrue();
		return result;
	}

	/**
	 * 요청 처리 등으로 세대를 사용하게 한 뒤 교체하기를 {@value #LEAK_CHECK_SWAPS}회 반복하고, 이전 세대의
	 * 클래스로더가 모두 수거되는지 확인한다.
	 */
	void assertPreviousGenerationsCollected(Runnable exercise) throws InterruptedException {
		List<WeakReference<ClassLoader>> previousLoaders = new ArrayList<>();
		for (int i = 0; i < LEAK_CHECK_SWAPS; i++) {
			previousLoaders.add(exerciseAndReload(exercise));
		}
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (countAlive(previousLoaders) > 0 && System.nanoTime() < deadline) {
			System.gc();
			Thread.sleep(100);
		}
		long alive = countAlive(previousLoaders);
		assertThat(alive)
			.as(() -> alive + " of " + LEAK_CHECK_SWAPS + " previous class loaders still reachable. Candidate caches:"
					+ LeakDiagnostics.describe(this.parentContext))
			.isZero();
	}

	/**
	 * 별도 메서드로 둬서 호출한 쪽 스택에 클래스로더의 강한 참조가 남지 않게 한다.
	 */
	private WeakReference<ClassLoader> exerciseAndReload(Runnable exercise) {
		WeakReference<ClassLoader> loader = new WeakReference<>(this.manager.current().classLoader());
		exercise.run();
		reload();
		return loader;
	}

	private static long countAlive(List<WeakReference<ClassLoader>> references) {
		return references.stream().filter((reference) -> reference.get() != null).count();
	}

	static void await(String description, BooleanSupplier condition) {
		await(description, TIMEOUT, condition);
	}

	static void await(String description, Duration timeout, BooleanSupplier condition) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + description);
			}
			sleep(Duration.ofMillis(50));
		}
	}

	static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			fail("Interrupted");
		}
	}

	URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

	HttpResponse<String> send(HttpRequest request) {
		try {
			return this.http.send(request, BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	HttpResponse<String> get(String path) {
		return send(HttpRequest.newBuilder(uri(path)).GET().build());
	}

	/**
	 * 200 응답의 본문. 200이 아니면 상태와 본문을 보여 주며 실패한다.
	 */
	String getOk(String path) {
		HttpResponse<String> response = get(path);
		assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
		return response.body();
	}

	Map<String, Object> getJson(String path) {
		return parse(getOk(path));
	}

	Map<String, Object> parse(String json) {
		try {
			return this.objectMapper.readValue(json, new TypeReference<>() {
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
