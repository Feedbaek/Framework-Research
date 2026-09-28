package com.example.greeting;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import com.example.greeting.shared.GreetingService;
import com.example.reload.generation.GenerationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 모드: 애플리케이션 클래스는 자식(재로딩 대상), {@code com.example.greeting.shared}만 부모 소유.
 * {@code reload.classpath}와 {@code reload.base-packages}는 설정하지 않았다(자동 감지).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GreetingReloadTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext parentContext;

	@Autowired
	private GreetingService greetingService;

	@Autowired
	private GenerationManager manager;

	@Test
	void parentOnlyRegistersParentOwnedBeans() {
		assertThat(this.parentContext.containsBean("defaultGreetingService")).isTrue();
		assertThat(this.parentContext.containsBean("helloController")).isFalse();
	}

	@Test
	void controllerIsServedByChildGenerationWithParentBean() throws Exception {
		Map<String, Object> body = getHello();

		assertThat(body).containsEntry("message", "v1").containsEntry("greeting", "Hello, reload!");
		assertThat((String) body.get("loader")).startsWith("GenerationClassLoader@");
		assertThat(body).containsEntry("sharedBean",
				Integer.toHexString(System.identityHashCode(this.greetingService)));
	}

	@Test
	void reloadCreatesNewChildLoaderAndKeepsParentBean() throws Exception {
		Map<String, Object> before = getHello();

		assertThat(this.manager.reload()).isTrue();
		Map<String, Object> after = getHello();

		assertThat(after.get("loader")).isNotEqualTo(before.get("loader"));
		assertThat(after.get("sharedBean")).isEqualTo(before.get("sharedBean"));
	}

	private Map<String, Object> getHello() throws IOException, InterruptedException {
		String json = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/hello?name=reload")).build(),
					BodyHandlers.ofString())
			.body();
		return new ObjectMapper().readValue(json, new TypeReference<>() {
		});
	}

}
