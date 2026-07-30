package my.spring.research.runtime.api;

public interface RuntimeMessageProvider extends RuntimePort {
	String messageFor(String operation);
}
