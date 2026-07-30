package my.spring.research.runtime.management;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import my.spring.research.runtime.deploy.DeploymentManager;
import my.spring.research.runtime.deploy.DeploymentStatus;

@Component("hotDeploy")
public class HotDeployHealthIndicator implements HealthIndicator {

	private final DeploymentManager deploymentManager;

	public HotDeployHealthIndicator(DeploymentManager deploymentManager) {
		this.deploymentManager = deploymentManager;
	}

	@Override
	public Health health() {
		DeploymentStatus active = deploymentManager.status().active();
		if ("EMPTY".equals(active.state())) {
			return Health.unknown()
					.withDetail("state", "NO_ACTIVE_DEPLOYMENT")
					.build();
		}
		return Health.up()
				.withDetail("deploymentId", active.deploymentId())
				.withDetail("appId", active.appId())
				.withDetail("version", active.version())
				.withDetail("activeLeases", active.activeLeases())
				.build();
	}
}
