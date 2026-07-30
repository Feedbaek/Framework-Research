package my.spring.research.runtime.web;

import java.util.Map;
import my.spring.research.runtime.deploy.DeploymentBusyException;
import my.spring.research.runtime.deploy.DeploymentException;
import my.spring.research.runtime.deploy.DeploymentRejectedException;
import my.spring.research.runtime.deploy.NoActiveDeploymentException;
import my.spring.research.runtime.loader.ArtifactValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RuntimeExceptionHandler {
	@ExceptionHandler(NoActiveDeploymentException.class)
	ResponseEntity<Map<String, String>> noActiveDeployment(NoActiveDeploymentException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "NO_ACTIVE_DEPLOYMENT", "message", ex.getMessage()));
	}

	@ExceptionHandler(DeploymentBusyException.class)
	ResponseEntity<Map<String, String>> deploymentBusy(DeploymentBusyException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "CONTROL_PLANE_BUSY", "message", ex.getMessage()));
	}

	@ExceptionHandler(DeploymentException.class)
	ResponseEntity<Map<String, String>> deploymentException(DeploymentException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "DEPLOYMENT_ERROR", "message", ex.getMessage()));
	}

	@ExceptionHandler(DeploymentRejectedException.class)
	ResponseEntity<Map<String, String>> deploymentRejected(DeploymentRejectedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("code", "DEPLOYMENT_REJECTED", "message", ex.getMessage()));
	}

	@ExceptionHandler(ArtifactValidationException.class)
	ResponseEntity<Map<String, String>> artifactValidation(ArtifactValidationException ex) {
		return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ARTIFACT", "message", ex.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Map<String, String>> illegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", ex.getMessage()));
	}
}
