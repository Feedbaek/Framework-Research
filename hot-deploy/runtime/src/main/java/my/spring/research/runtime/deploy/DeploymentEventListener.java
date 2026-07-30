package my.spring.research.runtime.deploy;

public interface DeploymentEventListener {
	default void deploymentActivated(DeploymentSlot slot) {
	}

	default void deploymentRetained(DeploymentSlot slot) {
	}

	default void drainTimedOut(DeploymentSlot slot) {
	}

	default void deploymentUnloaded(DeploymentSlot slot) {
	}

	default void deploymentFailed(DeploymentSlot slot) {
	}
}
