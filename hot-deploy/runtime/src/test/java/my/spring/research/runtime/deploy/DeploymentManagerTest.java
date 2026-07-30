package my.spring.research.runtime.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.config.HotDeployProperties;
import my.spring.research.runtime.loader.CandidateContextFactory;

final class DeploymentManagerTest {
	private final DrainManager drainManager = new DrainManager();
	private final DeploymentRouter router = new DeploymentRouter();
	private final DeploymentControlPlane controlPlane = new DeploymentControlPlane();
	private final HotDeployProperties properties = new HotDeployProperties();
	private final DeploymentManager manager = new DeploymentManager(null, router, controlPlane, drainManager, properties);

	@Test
	void cutoverKeepsExistingLeaseOnOldSlotAndRoutesNewLeasesToNewSlot() {
		AtomicBoolean v1Closed = new AtomicBoolean();
		DeploymentSlot v1 = new DeploymentSlot(FakeCandidates.candidate("v1", v1Closed));
		DeploymentSlot v2 = new DeploymentSlot(FakeCandidates.candidate("v2"));

		manager.activateSlot(v1);
		DeploymentLease oldLease = router.acquireLease();

		manager.activateSlot(v2);

		try (DeploymentLease newLease = router.acquireLease()) {
			assertThat(newLease.handler().handle(new BusinessRequest("op")).message()).isEqualTo("v2:op");
		}
		assertThat(oldLease.handler().handle(new BusinessRequest("op")).message()).isEqualTo("v1:op");
		assertThat(manager.status().retained()).hasSize(1);
		assertThat(manager.status().retained().getFirst().state()).isEqualTo("RETAINED_FOR_ROLLBACK");
		assertThat(manager.status().retained().getFirst().activeLeases()).isEqualTo(1);
		assertThat(v1Closed).isFalse();

		oldLease.close();
	}

	@Test
	void drainDoesNotCloseSlotUntilLastLeaseReturns() {
		properties.setRollbackRetentionCount(0);
		AtomicBoolean v1Closed = new AtomicBoolean();
		DeploymentSlot v1 = new DeploymentSlot(FakeCandidates.candidate("v1", v1Closed));
		DeploymentSlot v2 = new DeploymentSlot(FakeCandidates.candidate("v2"));

		manager.activateSlot(v1);
		DeploymentLease oldLease = router.acquireLease();
		manager.activateSlot(v2);

		assertThat(v1Closed).isFalse();
		assertThat(manager.status().unloading()).hasSize(1);
		assertThat(manager.status().unloading().getFirst().activeLeases()).isEqualTo(1);

		oldLease.close();

		assertThat(v1Closed).isTrue();
		assertThat(manager.status().unloading()).isEmpty();
	}

	@Test
	void rollbackReactivatesRetainedSlot() {
		DeploymentSlot v1 = new DeploymentSlot(FakeCandidates.candidate("v1"));
		DeploymentSlot v2 = new DeploymentSlot(FakeCandidates.candidate("v2"));

		manager.activateSlot(v1);
		manager.activateSlot(v2);
		manager.rollback("sha-v1");

		try (DeploymentLease lease = router.acquireLease()) {
			assertThat(lease.handler().handle(new BusinessRequest("op")).message()).isEqualTo("v1:op");
		}
		assertThat(manager.status().active().deploymentId()).isEqualTo("sha-v1");
	}

	@Test
	void pendingUnloadBlocksFurtherCutover() {
		DeploymentSlot v1 = new DeploymentSlot(FakeCandidates.candidate("v1"));
		DeploymentSlot v2 = new DeploymentSlot(FakeCandidates.candidate("v2"));
		DeploymentSlot v3 = new DeploymentSlot(FakeCandidates.candidate("v3"));
		DeploymentSlot v4 = new DeploymentSlot(FakeCandidates.candidate("v4"));

		manager.activateSlot(v1);
		DeploymentLease oldLease = router.acquireLease();
		manager.activateSlot(v2);
		manager.activateSlot(v3);

		assertThat(manager.status().unloading()).hasSize(1);
		assertThatThrownBy(() -> manager.activateSlot(v4))
				.isInstanceOf(DeploymentRejectedException.class)
				.hasMessageContaining("waiting for active leases");

		oldLease.close();
		manager.activateSlot(v4);

		assertThat(manager.status().active().deploymentId()).isEqualTo("sha-v4");
	}

	@Test
	void expiredRetainedSlotMovesToUnloadingAndClosesAfterLastLeaseReturns() {
		MutableClock clock = new MutableClock();
		HotDeployProperties retentionProperties = new HotDeployProperties();
		retentionProperties.setRollbackRetentionCount(1);
		retentionProperties.setRollbackRetentionTtl(Duration.ofSeconds(1));
		DrainManager clockedDrainManager = new DrainManager(clock);
		DeploymentRouter clockedRouter = new DeploymentRouter();
		DeploymentManager clockedManager = new DeploymentManager(
				null,
				clockedRouter,
				new DeploymentControlPlane(),
				clockedDrainManager,
				retentionProperties);
		AtomicBoolean v1Closed = new AtomicBoolean();
		DeploymentSlot v1 = new DeploymentSlot(FakeCandidates.candidate("v1", v1Closed), DeploymentState.READY, clock);
		DeploymentSlot v2 = new DeploymentSlot(FakeCandidates.candidate("v2"), DeploymentState.READY, clock);

		clockedManager.activateSlot(v1);
		DeploymentLease oldLease = clockedRouter.acquireLease();
		clockedManager.activateSlot(v2);
		clock.advance(Duration.ofSeconds(2));
		clockedManager.pruneExpiredRetained();

		assertThat(v1Closed).isFalse();
		assertThat(clockedManager.status().retained()).isEmpty();
		assertThat(clockedManager.status().unloading()).hasSize(1);

		oldLease.close();

		assertThat(v1Closed).isTrue();
		assertThat(clockedManager.status().unloading()).isEmpty();
	}

	@Test
	void zeroArtifactHistoryRemovesExpiredArtifactFromRollbackCatalog() {
		HotDeployProperties boundedProperties = new HotDeployProperties();
		boundedProperties.setRollbackRetentionCount(0);
		boundedProperties.setMaxArtifactHistory(0);
		CandidateContextFactory candidateFactory = mock(CandidateContextFactory.class);
		DeploymentManager boundedManager = new DeploymentManager(
				candidateFactory,
				new DeploymentRouter(),
				new DeploymentControlPlane(),
				new DrainManager(),
				boundedProperties);

		boundedManager.activate(FakeCandidates.candidate("v1"));
		boundedManager.activate(FakeCandidates.candidate("v2"));

		assertThatThrownBy(() -> boundedManager.rollback("sha-v1"))
				.isInstanceOf(DeploymentRejectedException.class)
				.hasMessageContaining("not available");
		verifyNoInteractions(candidateFactory);
	}

	private static final class MutableClock extends Clock {
		private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}
}
