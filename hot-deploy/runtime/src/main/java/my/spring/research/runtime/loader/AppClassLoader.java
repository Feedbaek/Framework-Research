package my.spring.research.runtime.loader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

final class AppClassLoader extends URLClassLoader {

	private static final String SHARED_API_PACKAGE = "my.spring.research.runtime.api.";
	private static final List<String> FORBIDDEN_PACKAGES = List.of(
			"my.spring.research.runtime.",
			"org.springframework.",
			"jakarta.servlet.",
			"javax.servlet."
	);

	AppClassLoader(String name, URL[] urls, ClassLoader parent) {
		super(name, urls, parent);
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (isForbidden(name)) {
			throw new ClassNotFoundException("App artifact is not allowed to access runtime framework class: " + name);
		}
		return super.loadClass(name, resolve);
	}

	private boolean isForbidden(String name) {
		if (name.startsWith(SHARED_API_PACKAGE)) {
			return false;
		}
		return FORBIDDEN_PACKAGES.stream().anyMatch(name::startsWith);
	}
}
