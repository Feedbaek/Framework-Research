package my.spring.research.runtime.api;

import java.util.List;

public interface AppModule {

	AppMetadata metadata();

	List<Class<?>> components();

	Class<? extends BusinessHandler> entryPoint();
}
