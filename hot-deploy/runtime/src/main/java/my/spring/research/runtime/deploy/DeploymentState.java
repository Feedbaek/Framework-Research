package my.spring.research.runtime.deploy;

public enum DeploymentState {
	READY,
	ACTIVE,
	DRAINING,
	DRAIN_TIMEOUT,
	RETAINED_FOR_ROLLBACK,
	UNLOAD_PENDING,
	UNLOADING,
	FAILED,
	UNLOADED
}
