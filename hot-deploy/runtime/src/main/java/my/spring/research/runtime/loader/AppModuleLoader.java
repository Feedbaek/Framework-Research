package my.spring.research.runtime.loader;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.ServiceLoader;

import org.springframework.stereotype.Component;

import my.spring.research.runtime.api.AppModule;

@Component
public class AppModuleLoader {

	public AppModule load(URLClassLoader classLoader) {
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		try {
			thread.setContextClassLoader(classLoader);
			ServiceLoader<AppModule> serviceLoader = ServiceLoader.load(AppModule.class, classLoader);
			ArrayList<AppModule> modules = new ArrayList<>();
			for (AppModule module : serviceLoader) {
				modules.add(module);
			}
			if (modules.size() != 1) {
				throw new ArtifactValidationException("artifact must provide exactly one AppModule, found " + modules.size());
			}
			return modules.getFirst();
		}
		finally {
			thread.setContextClassLoader(previous);
		}
	}
}
