package my.spring.research.runtime.loader;

public class ArtifactValidationException extends RuntimeException {

	public ArtifactValidationException(String message) {
		super(message);
	}

	public ArtifactValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
