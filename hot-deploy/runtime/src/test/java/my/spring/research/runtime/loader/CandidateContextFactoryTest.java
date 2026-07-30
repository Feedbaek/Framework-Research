package my.spring.research.runtime.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.RuntimeMessageProvider;
import my.spring.research.runtime.config.HotDeployProperties;

class CandidateContextFactoryTest {

	@TempDir
	Path tempDir;

	@Test
	void loadsSampleAppJarIntoChildContext() throws Exception {
		Path artifact = copySampleAppJarIntoRepository();
		GenericApplicationContext parent = parentContext();
		try (parent) {
			LoadedCandidate candidate = factory(parent).load(artifact);
			try (candidate) {
				assertEquals("sample-app", candidate.metadata().appId());
				assertEquals("1.0.0", candidate.metadata().version());
				assertEquals(
					"runtime message for hello",
					candidate.handler().handle(new BusinessRequest("hello")).message()
				);
				assertTrue(candidate.context().isActive());
			}
		}
	}

	@Test
	void rejectsArtifactsOutsideRepositoryRoot() {
		GenericApplicationContext parent = parentContext();
		try (parent) {
			Path outside = Path.of(System.getProperty("sampleAppJar"));
			assertThrows(ArtifactValidationException.class, () -> factory(parent).load(outside));
		}
	}

	private CandidateContextFactory factory(GenericApplicationContext parent) {
		HotDeployProperties properties = new HotDeployProperties();
		properties.setRepositoryRoot(tempDir.resolve("repository"));
		ArtifactValidator validator = new ArtifactValidator(properties);
		return new CandidateContextFactory(parent, validator, new AppClassLoaderFactory(), new AppModuleLoader());
	}

	private GenericApplicationContext parentContext() {
		GenericApplicationContext parent = new GenericApplicationContext();
		parent.registerBean(RuntimeMessageProvider.class, () -> operation -> "runtime message for " + operation);
		parent.refresh();
		return parent;
	}

	private Path copySampleAppJarIntoRepository() throws Exception {
		Path repository = tempDir.resolve("repository");
		Files.createDirectories(repository);
		Path artifact = repository.resolve("sample-app.jar");
		Files.copy(Path.of(System.getProperty("sampleAppJar")), artifact);
		return artifact;
	}
}
