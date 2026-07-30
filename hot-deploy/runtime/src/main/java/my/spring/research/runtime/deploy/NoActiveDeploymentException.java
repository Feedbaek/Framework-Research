package my.spring.research.runtime.deploy;

public class NoActiveDeploymentException extends RuntimeException {
	public NoActiveDeploymentException() {
		super("No active deployment is available");
	}
}
