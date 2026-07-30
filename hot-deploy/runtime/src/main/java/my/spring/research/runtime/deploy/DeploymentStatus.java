package my.spring.research.runtime.deploy;

public record DeploymentStatus(
		String deploymentId,
		String state,
		String appId,
		String version,
		String sha256,
		int activeLeases,
		String retainedUntil,
		String lastError
) {

	static DeploymentStatus from(DeploymentSlot slot) {
		var candidate = slot.candidate();
		return new DeploymentStatus(
			candidate.artifact().sha256(),
			slot.state().name(),
			candidate.metadata().appId(),
			candidate.metadata().version(),
			candidate.artifact().sha256(),
			slot.activeLeases(),
			slot.retainedUntil() == null ? null : slot.retainedUntil().toString(),
			slot.lastError()
		);
	}

	public static DeploymentStatus empty() {
		return new DeploymentStatus(null, "EMPTY", null, null, null, 0, null, null);
	}
}
