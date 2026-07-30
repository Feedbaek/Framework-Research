package my.spring.research.runtime.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceConfigurationError;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AppModuleLoaderTest {
	private static final String SERVICE_FILE =
			"META-INF/services/my.spring.research.runtime.api.AppModule";

	@TempDir
	Path tempDir;

	@Test
	void restoresThreadContextClassLoaderWhenServiceProviderCannotBeLoaded() throws IOException {
		Path artifact = tempDir.resolve("broken-provider.jar");
		writeJar(artifact, "missing.Provider\n");
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		AppModuleLoader loader = new AppModuleLoader();

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] { artifact.toUri().toURL() }, original)) {
			assertThatThrownBy(() -> loader.load(classLoader))
					.isInstanceOf(ServiceConfigurationError.class)
					.hasMessageContaining("Provider missing.Provider not found");
		}

		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(original);
	}

	@Test
	void rejectsArtifactsWithNoAppModuleProvider() throws IOException {
		Path artifact = tempDir.resolve("empty-provider.jar");
		writeJar(artifact, "");
		AppModuleLoader loader = new AppModuleLoader();

		try (URLClassLoader classLoader = new URLClassLoader(new URL[] { artifact.toUri().toURL() },
				Thread.currentThread().getContextClassLoader())) {
			assertThatThrownBy(() -> loader.load(classLoader))
					.isInstanceOf(ArtifactValidationException.class)
					.hasMessageContaining("exactly one AppModule, found 0");
		}
	}

	private void writeJar(Path path, String serviceFileBody) throws IOException {
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
			jar.putNextEntry(new JarEntry(SERVICE_FILE));
			jar.write(serviceFileBody.getBytes());
			jar.closeEntry();
		}
	}
}
