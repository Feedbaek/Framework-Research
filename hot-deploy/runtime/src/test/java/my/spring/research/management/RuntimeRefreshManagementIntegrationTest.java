package my.spring.research.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import my.spring.research.runtime.RuntimeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
		classes = RuntimeApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = {
				"management.server.port=0",
				"spring.cloud.compatibility-verifier.enabled=false",
				"spring.security.user.password=test-only-password"
		}
)
class RuntimeRefreshManagementIntegrationTest {

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int applicationPort;

	@LocalManagementPort
	private int managementPort;

	@Test
	void refreshIsManagementOnlyAuthenticatedAndAtomicallyValidated() throws Exception {
		assertThat(managementPort).isNotEqualTo(applicationPort);

		HttpResponse<String> mainResponse = post(applicationPort,
				"{\"runtime.message.prefix\":\"main:\"}", true);
		HttpResponse<String> unauthenticated = post(managementPort,
				"{\"runtime.message.prefix\":\"unauthenticated:\"}", false);
		HttpResponse<String> mixedCandidate = post(managementPort,
				"{\"runtime.message.prefix\":\"must-not-apply:\",\"server.port\":\"9999\"}", true);
		HttpResponse<String> safeCandidate = post(managementPort,
				"{\"runtime.message.prefix\":\"management:\"}", true);

		assertThat(mainResponse.statusCode()).isEqualTo(404);
		assertThat(unauthenticated.statusCode()).isEqualTo(401);
		assertThat(mixedCandidate.statusCode()).isEqualTo(400);
		assertThat(mixedCandidate.body()).contains("Refresh key is not allowed: server.port");
		assertThat(safeCandidate.statusCode()).isEqualTo(200);
		assertThat(safeCandidate.body()).contains("\"changed\":true");
	}

	private HttpResponse<String> post(int port, String body, boolean authenticated) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + "/actuator/runtimeRefresh"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (authenticated) {
			String credentials = Base64.getEncoder().encodeToString(
					"runtime-admin:test-only-password".getBytes(StandardCharsets.UTF_8));
			request.header("Authorization", "Basic " + credentials);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
