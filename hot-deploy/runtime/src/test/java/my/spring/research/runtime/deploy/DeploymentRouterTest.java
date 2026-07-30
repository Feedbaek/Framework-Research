package my.spring.research.runtime.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.BusinessResponse;

final class DeploymentRouterTest {
	@Test
	void acquireLeaseFailsWithoutActiveDeployment() {
		DeploymentRouter router = new DeploymentRouter();

		assertThatThrownBy(router::acquireLease).isInstanceOf(NoActiveDeploymentException.class);
	}

	@Test
	void selectionLeaseAndCutoverRouteRequestsToOneVersion() {
		DeploymentRouter router = new DeploymentRouter();
		var first = FakeCandidates.candidate("v1");
		var second = FakeCandidates.candidate("v2");

		router.activate(first);
		DeploymentLease firstLease = router.acquireLease();

		router.activate(second);

		assertThat(firstLease.handler().handle(new BusinessRequest("op")).message()).isEqualTo("v1:op");
		assertThat(router.route(new BusinessRequest("op")).message()).isEqualTo("v2:op");

		firstLease.close();
	}

	@Test
	void routeRestoresThreadContextClassLoader() {
		DeploymentRouter router = new DeploymentRouter();
		ClassLoader original = Thread.currentThread().getContextClassLoader();

		router.activate(FakeCandidates.candidate("v1"));
		router.route(new BusinessRequest("op"));

		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(original);
	}

	@Test
	void cutoverDoesNotWaitForInFlightHandlerAfterLeaseIsAcquired() throws Exception {
		DeploymentRouter router = new DeploymentRouter();
		CountDownLatch handlerEntered = new CountDownLatch(1);
		CountDownLatch releaseHandler = new CountDownLatch(1);
		router.activate(FakeCandidates.candidate("v1", new AtomicBoolean(), request -> {
			handlerEntered.countDown();
			try {
				assertThat(releaseHandler.await(1, TimeUnit.SECONDS)).isTrue();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ex);
			}
			return BusinessResponse.ok("v1:" + request.operation());
		}));
		var executor = Executors.newSingleThreadExecutor();

		try {
			var inFlight = executor.submit(() -> router.route(new BusinessRequest("op")));
			assertThat(handlerEntered.await(1, TimeUnit.SECONDS)).isTrue();

			router.activate(FakeCandidates.candidate("v2"));

			releaseHandler.countDown();
			assertThat(inFlight.get(1, TimeUnit.SECONDS).message()).isEqualTo("v1:op");
			assertThat(router.route(new BusinessRequest("op")).message()).isEqualTo("v2:op");
		}
		finally {
			releaseHandler.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void routeRestoresThreadContextClassLoaderWhenHandlerThrows() {
		DeploymentRouter router = new DeploymentRouter();
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		router.activate(FakeCandidates.candidate("v1", new AtomicBoolean(), request -> {
			throw new IllegalStateException("handler failed");
		}));

		assertThatThrownBy(() -> router.route(new BusinessRequest("op")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("handler failed");

		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(original);
	}
}
