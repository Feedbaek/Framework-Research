package my.spring.research.runtime.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

import my.spring.research.runtime.api.RuntimeMessageProvider;
import my.spring.research.runtime.config.HotDeployProperties;
import my.spring.research.runtime.loader.AppClassLoaderFactory;
import my.spring.research.runtime.loader.AppModuleLoader;
import my.spring.research.runtime.loader.ArtifactValidator;
import my.spring.research.runtime.loader.CandidateContextFactory;

class RedeployUnloadProbeTest {

	@TempDir
	Path tempDir;

	@Test
	void redeployUnloadsPreviousDeploymentWhenRetentionIsDisabled() throws Exception {
		HotDeployProperties properties = properties();
		properties.setRollbackRetentionCount(0);
		GenericApplicationContext parent = parentContext();
		try (parent) {
			DeploymentRouter router = new DeploymentRouter();
			DeploymentManager manager = manager(parent, router, properties);
			Path artifact = stageArtifact();

			try {
				manager.deploy(artifact);
				WeakReference<ClassLoader> first = trackActiveClassLoader(router);
				manager.deploy(artifact);

				assertTrue(awaitCleared(first),
						"previous app classloader survived redeploy even though retention is disabled");
			}
			finally {
				manager.shutdown();
			}
		}
	}

	@Test
	void redeployKeepsPreviousDeploymentAliveWhileRetentionWindowIsOpen() throws Exception {
		HotDeployProperties properties = properties();
		properties.setRollbackRetentionCount(1);
		properties.setRollbackRetentionTtl(Duration.ofMillis(200));
		GenericApplicationContext parent = parentContext();
		try (parent) {
			DeploymentRouter router = new DeploymentRouter();
			DeploymentManager manager = manager(parent, router, properties);
			Path artifact = stageArtifact();

			try {
				manager.deploy(artifact);
				WeakReference<ClassLoader> first = trackActiveClassLoader(router);
				manager.deploy(artifact);

				assertEquals(1, manager.status().retained().size(), "previous slot must be retained for rollback");
				assertEquals("RETAINED_FOR_ROLLBACK", manager.status().retained().getFirst().state());
				for (int i = 0; i < 5; i++) {
					System.gc();
					Thread.sleep(10);
				}
				assertNotNull(first.get(), "retained slot must stay strongly reachable so rollback can reactivate it");

				Thread.sleep(250);
				manager.pruneExpiredRetained();

				assertTrue(awaitCleared(first), "retained slot was not unloaded after its TTL expired");
			}
			finally {
				manager.shutdown();
			}
		}
	}

	@Test
	void slotWithActiveLeaseIsNotUnloadedUntilTheLeaseIsReturned() throws Exception {
		HotDeployProperties properties = properties();
		properties.setRollbackRetentionCount(0);
		GenericApplicationContext parent = parentContext();
		try (parent) {
			DeploymentRouter router = new DeploymentRouter();
			DeploymentManager manager = manager(parent, router, properties);
			Path artifact = stageArtifact();

			try {
				manager.deploy(artifact);
				DeploymentSlot first = router.activeSlot().orElseThrow();
				DeploymentLease inFlight = router.acquireLease();

				manager.deploy(artifact);

				assertEquals(DeploymentState.DRAINING, first.state(),
						"a slot with an in-flight request stays DRAINING and must not be closed");
				assertTrue(first.closeRequested(), "close must be requested so the last lease triggers unload");

				inFlight.close();

				assertEquals(DeploymentState.UNLOADED, first.state(),
						"returning the last lease must complete the pending unload");
			}
			finally {
				manager.shutdown();
			}
		}
	}

	private WeakReference<ClassLoader> trackActiveClassLoader(DeploymentRouter router) {
		return new WeakReference<>(router.activeSlot().orElseThrow().candidate().classLoader());
	}

	private boolean awaitCleared(WeakReference<?> ref) throws InterruptedException {
		for (int attempt = 0; attempt < 50 && ref.get() != null; attempt++) {
			System.gc();
			Thread.sleep(20);
		}
		return ref.get() == null;
	}

	private DeploymentManager manager(
			GenericApplicationContext parent,
			DeploymentRouter router,
			HotDeployProperties properties) {
		ArtifactValidator validator = new ArtifactValidator(properties);
		CandidateContextFactory factory =
				new CandidateContextFactory(parent, validator, new AppClassLoaderFactory(), new AppModuleLoader());
		return new DeploymentManager(factory, router, new DeploymentControlPlane(), new DrainManager(), properties);
	}

	private HotDeployProperties properties() {
		HotDeployProperties properties = new HotDeployProperties();
		properties.setRepositoryRoot(tempDir.resolve("repository"));
		return properties;
	}

	private GenericApplicationContext parentContext() {
		GenericApplicationContext parent = new GenericApplicationContext();
		parent.registerBean(RuntimeMessageProvider.class, () -> operation -> "runtime message for " + operation);
		parent.refresh();
		return parent;
	}

	private Path stageArtifact() throws Exception {
		Path repository = tempDir.resolve("repository");
		Files.createDirectories(repository);
		Path artifact = repository.resolve("sample-app.jar");
		if (!Files.exists(artifact)) {
			Files.copy(Path.of(System.getProperty("sampleAppJar")), artifact);
		}
		return artifact;
	}
}
