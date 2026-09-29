package com.example.reload.generation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.CookieManager;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 소비자 애플리케이션과 같은 배치로 띄운 테스트용 애플리케이션.
 * <p>
 * {@code @SpringBootApplication} 클래스, 부모 소유 패키지({@value #PARENT_PACKAGE}), 자식 소유 클래스를 모두 한
 * 출력 디렉터리에 컴파일하고, 그 디렉터리를 부모 클래스로더({@link URLClassLoader})에도 올린다. 그래서 부모와
 * 자식이 같은 디렉터리를 보고, {@code reload.classpath}는 설정하지 않아도 애플리케이션 클래스의 출력
 * 디렉터리로 정해진다. 엔진 통합 테스트({@link ControllerCompiler})는 자식 디렉터리가 부모 클래스패스에 없어서
 * 이 배치를 재현하지 못한다.
 */
final class ConsumerApp implements AutoCloseable {

	static final String PARENT_PACKAGE = "com.example.app.domain";

	static final String APPLICATION_CLASS = "com.example.app.DemoApplication";

	private static final String DEFAULT_APPLICATION = """
			package com.example.app;

			import org.springframework.boot.autoconfigure.SpringBootApplication;

			@SpringBootApplication
			public class DemoApplication {
			}
			""";

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final Path workDirectory;

	private final Path classesDirectory;

	private final Map<String, String> sources = new LinkedHashMap<>();

	private final CookieManager cookies = new CookieManager();

	private final HttpClient http = HttpClient.newBuilder().cookieHandler(this.cookies).build();

	private URLClassLoader classLoader;

	private ConfigurableApplicationContext context;

	private ConsumerApp(Path workDirectory) {
		this.workDirectory = workDirectory;
		this.classesDirectory = workDirectory.resolve("classes");
	}

	/**
	 * {@code api} 모드로 띄운다.
	 * @param sources 완전한 클래스 이름 → 소스. {@value #APPLICATION_CLASS}가 없으면 기본 애플리케이션 클래스를 쓴다.
	 */
	static ConsumerApp start(Map<String, String> sources) {
		return start(sources, List.of());
	}

	static ConsumerApp start(String triggerMode, Map<String, String> sources) {
		return start(sources, List.of("--reload.trigger.mode=" + triggerMode));
	}

	/**
	 * @param arguments 추가 명령행 인자. {@code --reload.parent-packages}와 {@code --reload.trigger.mode}가 없으면
	 * 각각 {@value #PARENT_PACKAGE}와 {@code api}를 쓴다.
	 */
	static ConsumerApp start(Map<String, String> sources, List<String> arguments) {
		ConsumerApp app = new ConsumerApp(AbstractReloadScenarioTest.createWorkDirectory("consumer"));
		app.sources.put(APPLICATION_CLASS, DEFAULT_APPLICATION);
		app.sources.putAll(sources);
		app.compile();
		try {
			app.run(arguments);
		}
		catch (RuntimeException ex) {
			try {
				app.close();
			}
			catch (IOException closeFailure) {
				ex.addSuppressed(closeFailure);
			}
			throw ex;
		}
		return app;
	}

	private void run(List<String> extraArguments) {
		List<String> arguments = new ArrayList<>(
				List.of("--server.port=0", "--reload.poll-interval=300ms", "--reload.quiet-period=100ms"));
		addDefault(arguments, extraArguments, "--reload.parent-packages=", PARENT_PACKAGE);
		addDefault(arguments, extraArguments, "--reload.trigger.mode=", "api");
		arguments.addAll(extraArguments);
		try {
			// 디렉터리가 이미 있으므로 URL은 '/'로 끝난다(URLClassLoader가 디렉터리로 취급).
			this.classLoader = new URLClassLoader(new URL[] { this.classesDirectory.toUri().toURL() },
					ConsumerApp.class.getClassLoader());
			Thread thread = Thread.currentThread();
			ClassLoader previous = thread.getContextClassLoader();
			thread.setContextClassLoader(this.classLoader);
			try {
				Class<?> applicationClass = this.classLoader.loadClass(APPLICATION_CLASS);
				SpringApplication application = new SpringApplication(new DefaultResourceLoader(this.classLoader),
						applicationClass);
				this.context = application.run(arguments.toArray(String[]::new));
			}
			finally {
				thread.setContextClassLoader(previous);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void addDefault(List<String> arguments, List<String> extraArguments, String prefix,
			String defaultValue) {
		if (extraArguments.stream().noneMatch((argument) -> argument.startsWith(prefix))) {
			arguments.add(prefix + defaultValue);
		}
	}

	/**
	 * 소스의 {@code package}와 첫 번째 public 타입 선언에서 완전한 클래스 이름을 뽑아 맵으로 만든다.
	 */
	static Map<String, String> sources(String... sources) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String source : sources) {
			result.put(className(source), source);
		}
		return result;
	}

	private static String className(String source) {
		Matcher packageMatcher = Pattern.compile("package\\s+([\\w.]+);").matcher(source);
		Matcher typeMatcher = Pattern
			.compile("public\\s+(?:final\\s+|abstract\\s+)*(?:class|interface|enum|record|@interface)\\s+(\\w+)")
			.matcher(source);
		if (!packageMatcher.find() || !typeMatcher.find()) {
			throw new IllegalArgumentException("Cannot determine class name of:\n" + source);
		}
		return packageMatcher.group(1) + "." + typeMatcher.group(1);
	}

	/**
	 * 소스를 바꾸거나 추가하고 전체를 다시 컴파일해서 출력 디렉터리를 교체한다(빌드 도구의 재컴파일과 같다).
	 * 재로딩은 하지 않는다.
	 */
	void update(Map<String, String> changedSources) {
		this.sources.putAll(changedSources);
		compile();
	}

	private void compile() {
		try {
			Path staging = Files.createTempDirectory(this.workDirectory, "compile-");
			List<String> sourceFiles = new ArrayList<>();
			for (Map.Entry<String, String> source : this.sources.entrySet()) {
				Path file = staging.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
				Files.createDirectories(file.getParent());
				Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
				sourceFiles.add(file.toString());
			}
			Path output = Files.createDirectories(staging.resolve("classes"));
			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
			List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-parameters", "-proc:none",
					"-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
			arguments.addAll(sourceFiles);
			if (compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)) != 0) {
				throw new IllegalStateException(
						"Compilation failed:\n" + diagnostics.toString(StandardCharsets.UTF_8));
			}
			replaceDirectory(output, this.classesDirectory);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void replaceDirectory(Path from, Path to) throws IOException {
		if (Files.exists(to)) {
			try (Stream<Path> stream = Files.walk(to)) {
				for (Path path : stream.filter((path) -> !path.equals(to)).sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(path);
				}
			}
		}
		Files.createDirectories(to);
		try (Stream<Path> stream = Files.walk(from)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				Files.createDirectories(target.getParent());
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	ConfigurableApplicationContext context() {
		return this.context;
	}

	GenerationManager manager() {
		return this.context.getBean(GenerationManager.class);
	}

	ReloadResult reload() {
		return manager().reloadWithResult();
	}

	/**
	 * 새 세대가 떠야 하는 재로딩.
	 */
	void reloadSuccessfully() {
		ReloadResult result = reload();
		assertThat(result.reloaded()).as("reload failed: %s", result.error()).isTrue();
	}

	/**
	 * 부모 클래스로더가 로드한(또는 로드할) 클래스.
	 */
	Class<?> parentClass(String name) throws ClassNotFoundException {
		return this.classLoader.loadClass(name);
	}

	ClassLoader currentGenerationClassLoader() {
		return manager().current().classLoader();
	}

	void clearCookies() {
		this.cookies.getCookieStore().removeAll();
	}

	HttpResponse<String> get(String path) {
		return send(HttpRequest.newBuilder(uri(path)).GET().build());
	}

	String getOk(String path) {
		HttpResponse<String> response = get(path);
		assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
		return response.body();
	}

	/**
	 * HTTP Basic 인증(비밀번호 {@code password})으로 GET 요청을 보낸다.
	 */
	HttpResponse<String> getAs(String username, String path) {
		String credentials = Base64.getEncoder()
			.encodeToString((username + ":password").getBytes(StandardCharsets.UTF_8));
		return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Basic " + credentials).GET().build());
	}

	HttpResponse<String> postJson(String path, String json) {
		return send(HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(json))
			.build());
	}

	private URI uri(String path) {
		int port = ((WebServerApplicationContext) this.context).getWebServer().getPort();
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest request) {
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

	/**
	 * 세대를 사용하고 교체하기를 반복한 뒤 이전 세대의 클래스로더가 모두 수거되는지 확인한다.
	 */
	void assertPreviousGenerationsCollected(Runnable exercise) throws InterruptedException {
		List<WeakReference<ClassLoader>> previousLoaders = new ArrayList<>();
		for (int i = 0; i < AbstractReloadScenarioTest.LEAK_CHECK_SWAPS; i++) {
			previousLoaders.add(exerciseAndReload(exercise));
		}
		long deadline = System.nanoTime() + TIMEOUT.toNanos();
		while (countAlive(previousLoaders) > 0 && System.nanoTime() < deadline) {
			System.gc();
			Thread.sleep(100);
		}
		long alive = countAlive(previousLoaders);
		assertThat(alive)
			.as(() -> alive + " of " + previousLoaders.size() + " previous class loaders still reachable. "
					+ "Candidate caches:" + LeakDiagnostics.describe(this.context))
			.isZero();
	}

	private WeakReference<ClassLoader> exerciseAndReload(Runnable exercise) {
		WeakReference<ClassLoader> loader = new WeakReference<>(currentGenerationClassLoader());
		exercise.run();
		reloadSuccessfully();
		return loader;
	}

	private static long countAlive(List<WeakReference<ClassLoader>> references) {
		return references.stream().filter((reference) -> reference.get() != null).count();
	}

	@Override
	public void close() throws IOException {
		if (this.context != null) {
			this.context.close();
		}
		if (this.classLoader != null) {
			this.classLoader.close();
		}
	}

}
