package my.spring.research.runtime.management;

import java.nio.file.Path;
import java.util.Map;
import my.spring.research.runtime.deploy.DeploymentBusyException;
import my.spring.research.runtime.deploy.DeploymentManager;
import my.spring.research.runtime.deploy.DeploymentRejectedException;
import my.spring.research.runtime.deploy.DeploymentStatus;
import my.spring.research.runtime.deploy.HotDeployStatus;
import my.spring.research.runtime.loader.ArtifactValidationException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint;
import org.springframework.stereotype.Component;

@Component
@WebEndpoint(id = "hotdeploy")
public class HotDeployEndpoint {
	private final DeploymentManager deploymentManager;

	public HotDeployEndpoint(DeploymentManager deploymentManager) {
		this.deploymentManager = deploymentManager;
	}

	@ReadOperation
	public HotDeployStatus status() {
		return deploymentManager.status();
	}

	@WriteOperation
	public WebEndpointResponse<?> change(
			String action,
			@Nullable String artifactPath,
			@Nullable String deploymentId
	) {
		try {
			DeploymentStatus status = switch (requireText(action, "action")) {
				case "deploy" -> deploymentManager.deploy(Path.of(requireText(artifactPath, "artifactPath")));
				case "rollback" -> deploymentManager.rollback(requireText(deploymentId, "deploymentId"));
				default -> throw new IllegalArgumentException("Unsupported hotdeploy action: " + action);
			};
			return new WebEndpointResponse<>(status, WebEndpointResponse.STATUS_OK);
		}
		catch (DeploymentBusyException | DeploymentRejectedException ex) {
			return error(409, "CONTROL_PLANE_CONFLICT", ex);
		}
		catch (ArtifactValidationException | IllegalArgumentException ex) {
			return error(WebEndpointResponse.STATUS_BAD_REQUEST, "INVALID_DEPLOYMENT_REQUEST", ex);
		}
	}

	private WebEndpointResponse<Map<String, String>> error(int status, String code, RuntimeException exception) {
		return new WebEndpointResponse<>(
				Map.of("code", code, "message", String.valueOf(exception.getMessage())),
				status
		);
	}

	private String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

}
