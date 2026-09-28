package com.example.reload.generation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code reload.trigger.mode=api}: 파일 변경으로는 재로딩하지 않고 재로딩 API 호출로만 재로딩한다.
 */
@SpringBootTest(classes = TestHostApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "reload.trigger.mode=api")
class ReloadApiIntegrationTest {

	private static final Path WORK_DIRECTORY = createWorkDirectory();

	private static final ControllerCompiler compiler = new ControllerCompiler(WORK_DIRECTORY,
			WORK_DIRECTORY.resolve("classes"));

	private final HttpClient httpClient = HttpClient.newHttpClient();

	private final ObjectMapper objectMapper = new ObjectMapper();

	@LocalServerPort
	private int port;

	@Autowired
	private GenerationManager manager;

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		compiler.deploy("v1");
		registry.add("reload.classpath", () -> WORK_DIRECTORY.resolve("classes").toString());
		registry.add("reload.base-packages", () -> "com.example.app");
		registry.add("reload.poll-interval", () -> "300ms");
		registry.add("reload.quiet-period", () -> "100ms");
	}

	@Test
	void fileChangesAloneDoNotReload() throws Exception {
		compiler.deploy("v1");
		assertThat(post("/_reload").statusCode()).isEqualTo(200);
		int generation = this.manager.currentGenerationId();

		compiler.deploy("v2");
		// watch 모드였다면 이 시간 안에 교체됐다(poll 300ms + quiet 100ms).
		Thread.sleep(1500);

		assertThat(this.manager.currentGenerationId()).isEqualTo(generation);
		assertThat(getJson("/hello")).containsEntry("message", "v1");
	}

	@Test
	void postReloadsAndReportsResult() throws Exception {
		int generationBefore = this.manager.currentGenerationId();
		compiler.deploy("v2");

		HttpResponse<String> response = post("/_reload");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(contentType) -> assertThat(contentType).startsWith("application/json"));
		Map<String, Object> result = parse(response.body());
		assertThat(result).containsEntry("reloaded", true)
			.containsEntry("previousGeneration", generationBefore)
			.containsEntry("generation", generationBefore + 1)
			.containsEntry("error", null);
		assertThat(getJson("/hello")).containsEntry("message", "v2");
	}

	@Test
	void failedReloadKeepsCurrentGenerationAndReportsCause() throws Exception {
		compiler.deploy("v2");
		assertThat(post("/_reload").statusCode()).isEqualTo(200);
		int generation = this.manager.currentGenerationId();

		compiler.deployBroken("v3");
		HttpResponse<String> response = post("/_reload");

		assertThat(response.statusCode()).isEqualTo(500);
		Map<String, Object> result = parse(response.body());
		assertThat(result).containsEntry("reloaded", false).containsEntry("generation", generation);
		assertThat((String) result.get("error")).contains("IllegalStateException").contains("broken controller");
		assertThat(getJson("/hello")).containsEntry("message", "v2");
	}

	@Test
	void getReturnsStatus() throws Exception {
		HttpResponse<String> response = this.httpClient.send(request("/_reload").GET().build(),
				BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(parse(response.body())).containsEntry("mode", "api")
			.containsEntry("generation", this.manager.currentGenerationId())
			.containsKey("failedReloads");
	}

	@Test
	void otherMethodsAreRejected() throws Exception {
		HttpResponse<String> response = this.httpClient
			.send(request("/_reload").PUT(BodyPublishers.noBody()).build(), BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(405);
	}

	private HttpResponse<String> post(String path) throws IOException, InterruptedException {
		return this.httpClient.send(request(path).POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
	}

	private Map<String, Object> getJson(String path) throws IOException, InterruptedException {
		HttpResponse<String> response = this.httpClient.send(request(path).GET().build(), BodyHandlers.ofString());
		assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
		return parse(response.body());
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path));
	}

	private Map<String, Object> parse(String json) throws IOException {
		return this.objectMapper.readValue(json, new TypeReference<>() {
		});
	}

	private static Path createWorkDirectory() {
		try {
			Path parent = Files.createDirectories(Path.of("build", "reload-it").toAbsolutePath());
			return Files.createTempDirectory(parent, "api-");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
