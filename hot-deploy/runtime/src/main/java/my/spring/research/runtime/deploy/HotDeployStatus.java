package my.spring.research.runtime.deploy;

import java.util.List;

public record HotDeployStatus(
		DeploymentStatus active,
		List<DeploymentStatus> retained,
		List<DeploymentStatus> unloading,
		List<DeploymentStatus> failed) {
}
