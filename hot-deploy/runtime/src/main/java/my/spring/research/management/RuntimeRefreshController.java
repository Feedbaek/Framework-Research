package my.spring.research.management;

import java.util.Map;

import my.spring.research.runtime.config.RuntimeRefreshCoordinator;
import my.spring.research.runtime.config.RuntimeRefreshResult;
import my.spring.research.runtime.deploy.DeploymentBusyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RuntimeRefreshController {

	private final RuntimeRefreshCoordinator coordinator;

	public RuntimeRefreshController(RuntimeRefreshCoordinator coordinator) {
		this.coordinator = coordinator;
	}

	@PostMapping(
			path = "${management.endpoints.web.base-path:/actuator}/runtimeRefresh",
			consumes = "application/json",
			produces = "application/json"
	)
	public ResponseEntity<?> refresh(@RequestBody Map<String, String> candidate) {
		try {
			RuntimeRefreshResult result = coordinator.refresh(candidate);
			return ResponseEntity.ok(result);
		}
		catch (DeploymentBusyException ex) {
			return error(HttpStatus.CONFLICT, "CONTROL_PLANE_BUSY", ex);
		}
		catch (IllegalArgumentException ex) {
			return error(HttpStatus.BAD_REQUEST, "INVALID_REFRESH_REQUEST", ex);
		}
	}

	private ResponseEntity<Map<String, String>> error(
			HttpStatus status,
			String code,
			RuntimeException exception
	) {
		return ResponseEntity.status(status).body(
				Map.of("code", code, "message", String.valueOf(exception.getMessage())));
	}
}
