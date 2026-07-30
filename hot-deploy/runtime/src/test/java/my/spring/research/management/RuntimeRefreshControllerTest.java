package my.spring.research.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import my.spring.research.runtime.config.RuntimeRefreshCoordinator;
import my.spring.research.runtime.config.RuntimeRefreshResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RuntimeRefreshControllerTest {

	private final RuntimeRefreshCoordinator coordinator = org.mockito.Mockito.mock(RuntimeRefreshCoordinator.class);
	private final RuntimeRefreshController controller = new RuntimeRefreshController(coordinator);

	@Test
	void forwardsTheCompletePropertyMapToTheCoordinator() {
		Map<String, String> candidate = Map.of(
				"runtime.message.prefix", "refreshed:",
				"server.port", "9999");
		when(coordinator.refresh(candidate))
				.thenReturn(new RuntimeRefreshResult(true, Set.of("runtime.message.prefix"), "refreshed"));

		var response = controller.refresh(candidate);

		verify(coordinator).refresh(candidate);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void returnsBadRequestWhenAnyPropertyIsRejected() {
		Map<String, String> candidate = Map.of("server.port", "9999");
		when(coordinator.refresh(candidate))
				.thenThrow(new IllegalArgumentException("Refresh key is not allowed: server.port"));

		var response = controller.refresh(candidate);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
