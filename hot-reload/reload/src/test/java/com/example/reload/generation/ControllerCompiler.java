package com.example.reload.generation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * 테스트용 {@code com.example.app.HelloController}의 여러 버전을 {@link JavaCompiler}로 컴파일해서
 * 감시 중인 클래스 디렉터리에 배포한다.
 */
final class ControllerCompiler {

	private static final String TEMPLATE = """
			package com.example.app;

			import java.util.LinkedHashMap;
			import java.util.Map;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RequestParam;
			import org.springframework.web.bind.annotation.RestController;

			import com.example.reload.fixture.GreetingService;

			@RestController
			public class HelloController {

				private final GreetingService greetingService;

				public HelloController(GreetingService greetingService) {
					%s
					this.greetingService = greetingService;
				}

				@GetMapping("/hello")
				public Map<String, Object> hello() {
					ClassLoader loader = HelloController.class.getClassLoader();
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("message", "%s");
					body.put("loader", Integer.toHexString(System.identityHashCode(loader)));
					body.put("sharedBean", Integer.toHexString(System.identityHashCode(this.greetingService)));
					return body;
				}

				@GetMapping("/slow")
				public Map<String, Object> slow(@RequestParam("ms") long ms) throws InterruptedException {
					Thread.sleep(ms);
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("message", "%s");
					return body;
				}

				private static void failOnStartup() {
					throw new IllegalStateException("broken controller");
				}

			}
			""";

	private final Path workDirectory;

	private final Path classesDirectory;

	ControllerCompiler(Path workDirectory, Path classesDirectory) {
		this.workDirectory = workDirectory;
		this.classesDirectory = classesDirectory;
	}

	/**
	 * 정상 동작하는 버전을 배포한다.
	 */
	void deploy(String version) {
		deploy(version, "");
	}

	/**
	 * 생성자에서 예외를 던져 자식 context refresh가 실패하는 버전을 배포한다.
	 */
	void deployBroken(String version) {
		deploy(version, "failOnStartup();");
	}

	private void deploy(String version, String constructorStatement) {
		deploySources(version, Map.of("HelloController", TEMPLATE.formatted(constructorStatement, version, version)));
	}

	/**
	 * {@code com.example.app} 패키지의 소스들을 함께 컴파일해서 배포한다.
	 * @param label 임시 디렉터리 이름에 쓰는 표시
	 * @param sources 단순 클래스 이름 → 소스
	 */
	void deploySources(String label, Map<String, String> sources) {
		try {
			Path staging = Files.createTempDirectory(this.workDirectory, "compile-" + label + "-");
			Path sourceDirectory = Files.createDirectories(staging.resolve("src/com/example/app"));
			List<String> sourceFiles = new ArrayList<>();
			for (Map.Entry<String, String> source : sources.entrySet()) {
				Path sourceFile = sourceDirectory.resolve(source.getKey() + ".java");
				Files.writeString(sourceFile, source.getValue(), StandardCharsets.UTF_8);
				sourceFiles.add(sourceFile.toString());
			}
			Path output = Files.createDirectories(staging.resolve("classes"));
			compile(sourceFiles, output);
			// 컴파일이 끝난 결과만 감시 디렉터리로 복사한다.
			copyClasses(output, this.classesDirectory);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static void compile(List<String> sourceFiles, Path output) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		List<String> arguments = new ArrayList<>(List.of("-encoding", "UTF-8", "-parameters", "-proc:none",
				"-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
		arguments.addAll(sourceFiles);
		int result = compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
		if (result != 0) {
			throw new IllegalStateException("Compilation failed:\n" + diagnostics.toString(StandardCharsets.UTF_8));
		}
	}

	private static void copyClasses(Path from, Path to) throws IOException {
		List<Path> files;
		try (Stream<Path> stream = Files.walk(from)) {
			files = stream.filter(Files::isRegularFile).toList();
		}
		for (Path file : files) {
			Path target = to.resolve(from.relativize(file).toString());
			Files.createDirectories(target.getParent());
			Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

}
