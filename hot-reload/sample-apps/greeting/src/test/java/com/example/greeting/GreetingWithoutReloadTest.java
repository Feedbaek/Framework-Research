package com.example.greeting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import com.example.reload.generation.GenerationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code reload.enabled=false}: 엔진이 꺼지고 같은 코드가 평범한 Spring MVC 애플리케이션으로 동작한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "reload.enabled=false")
class GreetingWithoutReloadTest {

	@LocalServerPort
	private int port;

	@Autowired
	private ApplicationContext context;

	@Test
	void runsAsPlainSpringMvcApplication() throws Exception {
		assertThat(this.context.getBeanNamesForType(GenerationManager.class)).isEmpty();
		assertThat(this.context.containsBean("helloController")).isTrue();

		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + "/hello")).build(),
					BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"message\":\"v1\"").doesNotContain("GenerationClassLoader");
	}

}
