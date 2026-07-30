package my.spring.research.runtime.loader;

import java.net.MalformedURLException;
import java.net.URLClassLoader;

import org.springframework.stereotype.Component;

@Component
public class AppClassLoaderFactory {

	public URLClassLoader create(AppArtifact artifact) {
		try {
			return new AppClassLoader(
				"app-" + artifact.sha256(),
				new java.net.URL[] { artifact.path().toUri().toURL() },
				AppClassLoaderFactory.class.getClassLoader()
			);
		}
		catch (MalformedURLException ex) {
			throw new ArtifactValidationException("invalid artifact URL: " + artifact.path(), ex);
		}
	}
}
