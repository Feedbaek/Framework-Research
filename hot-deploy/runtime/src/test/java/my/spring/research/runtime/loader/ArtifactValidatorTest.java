package my.spring.research.runtime.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import my.spring.research.runtime.config.HotDeployProperties;

final class ArtifactValidatorTest {
	private static final String SERVICE_FILE =
			"META-INF/services/my.spring.research.runtime.api.AppModule";

	@TempDir
	Path tempDir;

	@Test
	void acceptsRegularJarUnderRepositoryRootWhenAppModuleServiceExists() throws IOException {
		Path repository = Files.createDirectory(tempDir.resolve("repository"));
		Path artifact = repository.resolve("app.jar");
		writeJar(artifact, Map.of(
				SERVICE_FILE, "sample.AppModule\n",
				"sample/AppModule.class", "bytes"));
		ArtifactValidator validator = new ArtifactValidator(properties(repository));

		AppArtifact validated = validator.validate(artifact);

		assertThat(validated.path()).isEqualTo(artifact.toRealPath());
		assertThat(validated.sha256()).hasSize(64);
		assertThat(validated.size()).isGreaterThan(0);
	}

	@Test
	void rejectsJarOutsideRepositoryRoot() throws IOException {
		Path repository = Files.createDirectory(tempDir.resolve("repository"));
		Path outside = tempDir.resolve("outside.jar");
		writeJar(outside, Map.of(SERVICE_FILE, "sample.AppModule\n"));
		ArtifactValidator validator = new ArtifactValidator(properties(repository));

		assertThatThrownBy(() -> validator.validate(outside))
				.isInstanceOf(ArtifactValidationException.class)
				.hasMessageContaining("outside repository root");
	}

	@Test
	void rejectsJarWithoutAppModuleServiceProvider() throws IOException {
		Path repository = Files.createDirectory(tempDir.resolve("repository"));
		Path artifact = repository.resolve("app.jar");
		writeJar(artifact, Map.of("sample/AppModule.class", "bytes"));
		ArtifactValidator validator = new ArtifactValidator(properties(repository));

		assertThatThrownBy(() -> validator.validate(artifact))
				.isInstanceOf(ArtifactValidationException.class)
				.hasMessageContaining("does not declare AppModule");
	}

	@Test
	void rejectsForbiddenRuntimeClassesInAppArtifact() throws IOException {
		Path repository = Files.createDirectory(tempDir.resolve("repository"));
		Path artifact = repository.resolve("app.jar");
		writeJar(artifact, Map.of(
				SERVICE_FILE, "sample.AppModule\n",
				"my/spring/research/runtime/api/AppModule.class", "bytes"));
		ArtifactValidator validator = new ArtifactValidator(properties(repository));

		assertThatThrownBy(() -> validator.validate(artifact))
				.isInstanceOf(ArtifactValidationException.class)
				.hasMessageContaining("forbidden class");
	}

	@Test
	void rejectsArtifactsLargerThanConfiguredCompressedLimit() throws IOException {
		Path repository = Files.createDirectory(tempDir.resolve("repository"));
		Path artifact = repository.resolve("app.jar");
		writeJar(artifact, Map.of(
				SERVICE_FILE, "sample.AppModule\n",
				"sample/Large.class", "0123456789"));
		HotDeployProperties properties = properties(repository);
		properties.setMaxArtifactBytes(1);
		ArtifactValidator validator = new ArtifactValidator(properties);

		assertThatThrownBy(() -> validator.validate(artifact))
				.isInstanceOf(ArtifactValidationException.class)
				.hasMessageContaining("too large");
	}

	private HotDeployProperties properties(Path repository) {
		HotDeployProperties properties = new HotDeployProperties();
		properties.setRepositoryRoot(repository);
		properties.setAllowedClassPrefixes(Set.of("sample/"));
		return properties;
	}

	private void writeJar(Path path, Map<String, String> entries) throws IOException {
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
			for (var entry : entries.entrySet()) {
				jar.putNextEntry(new JarEntry(entry.getKey()));
				jar.write(entry.getValue().getBytes());
				jar.closeEntry();
			}
		}
	}
}
