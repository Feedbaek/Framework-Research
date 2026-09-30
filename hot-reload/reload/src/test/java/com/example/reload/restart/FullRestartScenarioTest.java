package com.example.reload.restart;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** 실제 별도 JVM과 HTTP로 부분 교체/전체 재시작/시작 실패를 함께 검증한다. */
class FullRestartScenarioTest {
	@TempDir Path directory;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private Process supervisor;
	private long pid;
	private int port;

	@Test
	@Timeout(90)
	void businessRetainsPidAndResourceWhileInfrastructureRestartsTheProcess() throws Exception {
		compile("v1", "infra1", false);
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String testClasspath = System.getProperty("java.class.path");
		String appClasspath = this.directory.resolve("classes") + File.pathSeparator + testClasspath;
		Path log = this.directory.resolve("process.log");
		this.supervisor = new ProcessBuilder(java, "-cp", testClasspath, RestartLauncher.class.getName(), "--",
				java, "-cp", appClasspath, "com.example.supervised.App", "--server.port=0",
				"--reload.business-packages=com.example.supervised.web", "--reload.trigger.mode=api",
				"--probe.ready=" + this.directory.resolve("ready"))
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			awaitReady(0);
			long firstPid = this.pid;
			String first = get("/value").body();
			assertThat(first).startsWith("v1:infra1:");
			compile("v2", "infra1", false);
			assertThat(reload().statusCode()).isEqualTo(200);
			assertThat(get("/value").body()).isEqualTo(first.replace("v1:", "v2:"));
			assertThat(get("/pid").body()).isEqualTo(Long.toString(firstPid));

			// 부모와 업무 코드가 함께 바뀌면 202를 받고 새 JVM에서 둘 다 적용한다.
			compile("v3", "infra2", false);
			HttpResponse<String> restart = reload();
			assertThat(restart.statusCode()).isEqualTo(202);
			assertThat(restart.body()).contains("restart-requested");
			awaitReady(firstPid);
			assertThat(this.pid).isNotEqualTo(firstPid);
			String third = get("/value").body();
			assertThat(third).startsWith("v3:infra2:");
			assertThat(third.substring("v3:infra2:".length())).isNotEqualTo(first.substring("v1:infra1:".length()));

			// 자식 생성 실패는 서비스 중인 세대를 보존한다.
			compile("broken", "infra2", true);
			assertThat(reload().statusCode()).isEqualTo(500);
			assertThat(get("/value").body()).isEqualTo(third);

			// 전체 재시작 후 시작 실패는 감독자까지 종료한다(무한 재시작 방지).
			compile("broken", "infra3", true);
			assertThat(reload().statusCode()).isEqualTo(202);
			assertThat(this.supervisor.waitFor(25, TimeUnit.SECONDS)).as(Files.readString(log)).isTrue();
			assertThat(this.supervisor.exitValue()).isNotZero();
		}
		finally {
			if (this.supervisor.isAlive()) {
				this.supervisor.destroy();
				if (!this.supervisor.waitFor(10, TimeUnit.SECONDS)) {
					this.supervisor.descendants().forEach(ProcessHandle::destroyForcibly);
					this.supervisor.destroyForcibly();
				}
			}
		}
	}

	private void awaitReady(long previousPid) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
		Path ready = this.directory.resolve("ready");
		while (System.nanoTime() < deadline && this.supervisor.isAlive()) {
			if (Files.isRegularFile(ready)) {
				String[] values = Files.readString(ready).trim().split(",");
				if (values.length == 2 && Long.parseLong(values[0]) != previousPid) {
					this.pid = Long.parseLong(values[0]);
					this.port = Integer.parseInt(values[1]);
					return;
				}
			}
			Thread.sleep(100);
		}
		fail("Application did not become ready:\n" + Files.readString(this.directory.resolve("process.log")));
	}

	private HttpResponse<String> get(String path) throws Exception {
		return this.http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> reload() throws Exception {
		return this.http.send(request("/_reload").POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).timeout(Duration.ofSeconds(10));
	}

	private void compile(String business, String infrastructure, boolean broken) throws Exception {
		Map<String, String> sources = Map.of("com.example.supervised.App", """
			package com.example.supervised;
			@org.springframework.boot.autoconfigure.SpringBootApplication
			public class App {
				public static void main(String[] args) throws Exception {
					var context = org.springframework.boot.SpringApplication.run(App.class, args);
					var env = context.getEnvironment();
					java.nio.file.Files.writeString(java.nio.file.Path.of(env.getRequiredProperty("probe.ready")),
						ProcessHandle.current().pid() + "," + env.getRequiredProperty("local.server.port"));
				}
			}
			""", "com.example.supervised.infra.Resource", """
			package com.example.supervised.infra;
			@org.springframework.stereotype.Component
			public class Resource {
				private final String id = java.util.UUID.randomUUID().toString();
				public String value() { return "%s:" + id; }
			}
			""".formatted(infrastructure), "com.example.supervised.web.Business", """
			package com.example.supervised.web;
			@org.springframework.web.bind.annotation.RestController
			public class Business {
				private final com.example.supervised.infra.Resource resource;
				public Business(com.example.supervised.infra.Resource resource) {
					this.resource = resource;
					%s
				}
				@org.springframework.web.bind.annotation.GetMapping("/value")
				public String value() { return "%s:" + resource.value(); }
				@org.springframework.web.bind.annotation.GetMapping("/pid")
				public long pid() { return ProcessHandle.current().pid(); }
			}
			""".formatted(broken ? "throw new IllegalStateException(\"broken generation\");" : "", business));
		Path output = Files.createDirectories(this.directory.resolve("classes"));
		List<String> arguments = new ArrayList<>(List.of("-parameters", "-proc:none", "-classpath",
				System.getProperty("java.class.path"), "-d", output.toString()));
		for (var source : sources.entrySet()) {
			Path file = this.directory.resolve("src").resolve(source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			arguments.add(file.toString());
		}
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		assertThat(ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)))
				.as(diagnostics.toString(StandardCharsets.UTF_8)).isZero();
	}
}
