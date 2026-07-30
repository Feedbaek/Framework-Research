package my.spring.research.runtime.deploy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import my.spring.research.runtime.loader.LoadedCandidate;

public final class DeploymentSlot implements AutoCloseable {

	private final LoadedCandidate candidate;
	private final Clock clock;
	private final Instant createdAt;
	private final AtomicReference<DeploymentState> state = new AtomicReference<>(DeploymentState.READY);
	private final AtomicInteger leases = new AtomicInteger();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final CopyOnWriteArrayList<Consumer<DeploymentSlot>> unloadListeners = new CopyOnWriteArrayList<>();
	private volatile Instant stateChangedAt;
	private volatile Instant retainedUntil;
	private volatile String lastError;
	private volatile boolean closeRequested;

	public DeploymentSlot(LoadedCandidate candidate) {
		this(candidate, Clock.systemUTC());
	}

	public DeploymentSlot(LoadedCandidate candidate, DeploymentState initialState, Clock clock) {
		this.candidate = candidate;
		this.clock = clock;
		this.createdAt = clock.instant();
		this.stateChangedAt = createdAt;
		this.state.set(initialState);
	}

	DeploymentSlot(LoadedCandidate candidate, Clock clock) {
		this(candidate, DeploymentState.READY, clock);
	}

	public LoadedCandidate candidate() {
		return candidate;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public DeploymentState state() {
		return state.get();
	}

	public Instant stateChangedAt() {
		return stateChangedAt;
	}

	public Instant retainedUntil() {
		return retainedUntil;
	}

	public String lastError() {
		return lastError;
	}

	public boolean closeRequested() {
		return closeRequested;
	}

	public int activeLeases() {
		return leases.get();
	}

	public void onUnloaded(Consumer<DeploymentSlot> listener) {
		unloadListeners.add(listener);
	}

	public void activate() {
		transitionTo(DeploymentState.ACTIVE);
	}

	public void drain() {
		transitionTo(DeploymentState.DRAINING);
	}

	public void retainFor(Duration ttl) {
		retainedUntil = clock.instant().plus(ttl);
		transitionTo(DeploymentState.RETAINED_FOR_ROLLBACK);
	}

	public boolean retentionExpired() {
		return retainedUntil != null && !clock.instant().isBefore(retainedUntil);
	}

	public void requestClose() {
		closeRequested = true;
	}

	public void transitionTo(DeploymentState next) {
		state.set(next);
		stateChangedAt = clock.instant();
	}

	public void markFailed(Throwable failure) {
		lastError = failure.getClass().getName() + ": " + failure.getMessage();
		transitionTo(DeploymentState.FAILED);
	}

	public void markFailed(Throwable failure, DeploymentState failureState) {
		lastError = failure.getClass().getName() + ": " + failure.getMessage();
		transitionTo(failureState);
	}

	public void closeCandidate() throws Exception {
		candidate.close();
	}

	public DeploymentLease acquireLease() {
		if (state.get() != DeploymentState.ACTIVE) {
			throw new DeploymentException("deployment is not active");
		}
		leases.incrementAndGet();
		if (state.get() != DeploymentState.ACTIVE) {
			releaseLease();
			throw new DeploymentException("deployment is not active");
		}
		return new DeploymentLease(this);
	}

	void releaseLease() {
		int remaining = leases.decrementAndGet();
		if (remaining < 0) {
			throw new IllegalStateException("deployment lease count became negative");
		}
		if (remaining == 0 && closeRequested) {
			close();
		}
	}

	@Override
	public void close() {
		List<Consumer<DeploymentSlot>> listenersToNotify = List.of();
		synchronized (this) {
			requestClose();
			if (leases.get() > 0) {
				if (state.get() != DeploymentState.RETAINED_FOR_ROLLBACK) {
					transitionTo(DeploymentState.UNLOAD_PENDING);
				}
				return;
			}
			if (closed.compareAndSet(false, true)) {
				transitionTo(DeploymentState.UNLOADING);
				try {
					closeCandidate();
					transitionTo(DeploymentState.UNLOADED);
					listenersToNotify = List.copyOf(unloadListeners);
					unloadListeners.clear();
				}
				catch (Exception ex) {
					markFailed(ex);
				}
			}
		}
		listenersToNotify.forEach(listener -> listener.accept(this));
	}
}
