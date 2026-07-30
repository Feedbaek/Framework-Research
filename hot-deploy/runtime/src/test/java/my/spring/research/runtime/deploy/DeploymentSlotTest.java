package my.spring.research.runtime.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class DeploymentSlotTest {

	@Test
	void unloadListenersRunOutsideSlotMonitor() {
		DeploymentSlot slot = new DeploymentSlot(FakeCandidates.candidate("v1"));
		AtomicBoolean callbackHeldSlotMonitor = new AtomicBoolean(true);
		slot.onUnloaded(unloaded -> callbackHeldSlotMonitor.set(Thread.holdsLock(unloaded)));

		slot.close();

		assertThat(slot.state()).isEqualTo(DeploymentState.UNLOADED);
		assertThat(callbackHeldSlotMonitor).isFalse();
	}
}
