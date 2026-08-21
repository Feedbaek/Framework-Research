package my.spring.research.runtime.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.RuntimeMessageProvider;
import my.spring.research.runtime.config.HotDeployProperties;

class ClassLoaderUnloadProbeTest {

	@TempDir
	Path tempDir;

	@Test
	void appClassLoaderBecomesUnreachableAfterCandidateClose() throws Exception {
		GenericApplicationContext parent = parentContext();
		try (parent) {
			WeakReference<ClassLoader> ref = loadInvokeAndClose(parent);
			assertTrue(awaitCleared(ref),
					"app classloader is still strongly reachable after LoadedCandidate.close()");
		}
	}

	@Test
	void appClassesBecomeUnreachableAfterCandidateClose() throws Exception {
		GenericApplicationContext parent = parentContext();
		try (parent) {
			WeakReference<Class<?>> ref = loadInvokeAndCloseTrackingHandlerClass(parent);
			assertTrue(awaitCleared(ref),
					"app handler class is still strongly reachable after LoadedCandidate.close()");
		}
	}

	@Test
	void autoCloseableBeanIsDestroyedByChildContext() {
		GenericApplicationContext context = new GenericApplicationContext();
		RootBeanDefinition definition = new RootBeanDefinition(ProbeResource.class);
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		context.registerBeanDefinition("app@probe:resource", definition);
		context.refresh();

		ProbeResource resource = context.getBean(ProbeResource.class);
		assertFalse(resource.closed, "resource must be open while the context is active");

		context.close();
		assertTrue(resource.closed, "AutoCloseable.close() was not inferred as the destroy method");
	}

	private WeakReference<ClassLoader> loadInvokeAndClose(GenericApplicationContext parent) throws Exception {
		LoadedCandidate candidate = factory(parent).load(copySampleAppJarIntoRepository());
		candidate.handler().handle(new BusinessRequest("hello"));
		WeakReference<ClassLoader> ref = new WeakReference<>(candidate.classLoader());
		candidate.close();
		return ref;
	}

	private WeakReference<Class<?>> loadInvokeAndCloseTrackingHandlerClass(GenericApplicationContext parent)
			throws Exception {
		LoadedCandidate candidate = factory(parent).load(copySampleAppJarIntoRepository());
		candidate.handler().handle(new BusinessRequest("hello"));
		WeakReference<Class<?>> ref = new WeakReference<>(candidate.handler().getClass());
		candidate.close();
		return ref;
	}

	private boolean awaitCleared(WeakReference<?> ref) throws InterruptedException {
		for (int attempt = 0; attempt < 50 && ref.get() != null; attempt++) {
			System.gc();
			Thread.sleep(20);
		}
		return ref.get() == null;
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
		if (!Files.exists(artifact)) {
			Files.copy(Path.of(System.getProperty("sampleAppJar")), artifact);
		}
		return artifact;
	}

	public static class ProbeResource implements AutoCloseable {

		boolean closed;

		public ProbeResource() {
		}

		@Override
		public void close() {
			this.closed = true;
		}
	}
}
