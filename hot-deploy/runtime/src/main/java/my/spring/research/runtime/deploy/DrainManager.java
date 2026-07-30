package my.spring.research.runtime.deploy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

@Component
public class DrainManager {
	private final Clock clock;
	private final List<DeploymentEventListener> listeners = new CopyOnWriteArrayList<>();

	public DrainManager() {
		this(Clock.systemUTC());
	}

	DrainManager(Clock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public void addListener(DeploymentEventListener listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	public void retainForRollback(DeploymentSlot slot, Duration ttl) {
		slot.retainFor(ttl);
		listeners.forEach(listener -> listener.deploymentRetained(slot));
	}

	public boolean unloadIfIdle(DeploymentSlot slot) {
		Objects.requireNonNull(slot, "slot");
		if (slot.activeLeases() > 0) {
			slot.requestClose();
			if (slot.state() != DeploymentState.DRAINING && slot.state() != DeploymentState.RETAINED_FOR_ROLLBACK) {
				slot.transitionTo(DeploymentState.UNLOAD_PENDING);
			}
			return false;
		}
		return closeNow(slot);
	}

	public boolean drainOrRetain(DeploymentSlot slot, Duration drainTimeout, Duration rollbackTtl, boolean retain) {
		Objects.requireNonNull(slot, "slot");
		if (retain) {
			retainForRollback(slot, rollbackTtl);
			return false;
		}
		if (slot.activeLeases() == 0) {
			return closeNow(slot);
		}
		slot.requestClose();
		if (isDrainTimedOut(slot, drainTimeout)) {
			listeners.forEach(listener -> listener.drainTimedOut(slot));
		}
		return false;
	}

	boolean expireRetention(DeploymentSlot slot) {
		if (slot.state() == DeploymentState.RETAINED_FOR_ROLLBACK && slot.retentionExpired()) {
			slot.transitionTo(DeploymentState.UNLOAD_PENDING);
			return unloadIfIdle(slot);
		}
		return false;
	}

	void checkDrainTimeout(DeploymentSlot slot, Duration timeout) {
		if (slot.state() == DeploymentState.DRAINING && isDrainTimedOut(slot, timeout)) {
			slot.transitionTo(DeploymentState.DRAIN_TIMEOUT);
			listeners.forEach(listener -> listener.drainTimedOut(slot));
		}
	}

	private boolean isDrainTimedOut(DeploymentSlot slot, Duration timeout) {
		return !clock.instant().isBefore(slot.stateChangedAt().plus(timeout));
	}

	private boolean closeNow(DeploymentSlot slot) {
		if (slot.state() == DeploymentState.UNLOADED || slot.state() == DeploymentState.UNLOADING) {
			return slot.state() == DeploymentState.UNLOADED;
		}
		try {
			slot.close();
			if (slot.state() == DeploymentState.UNLOADED) {
				listeners.forEach(listener -> listener.deploymentUnloaded(slot));
				return true;
			}
			listeners.forEach(listener -> listener.deploymentFailed(slot));
			return false;
		} catch (Exception e) {
			slot.markFailed(e);
			listeners.forEach(listener -> listener.deploymentFailed(slot));
			return false;
		}
	}
}
