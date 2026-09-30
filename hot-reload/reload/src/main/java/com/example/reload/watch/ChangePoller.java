package com.example.reload.watch;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** 내용이 quietPeriod 동안 안정된 경우에만 전달한다. 실패 시 같은 변경을 무한 재시도하지 않는다. */
public final class ChangePoller implements AutoCloseable {
	private static final Log logger = LogFactory.getLog(ChangePoller.class);
	private final ChangePlanner planner;
	private final Consumer<ChangePlanner.Snapshot> action;
	private final ScheduledExecutorService executor;
	private final long quietNanos;
	private ChangePlanner.Snapshot observed;
	private ChangePlanner.Snapshot delivered;
	private long changedAt;

	public ChangePoller(ChangePlanner planner, ChangePlanner.Snapshot initial, Duration interval, Duration quiet,
			ClassLoader loader, Consumer<ChangePlanner.Snapshot> action) {
		if (interval.isZero() || interval.isNegative() || quiet.isNegative()) {
			throw new IllegalArgumentException("poll-interval must be positive and quiet-period must not be negative");
		}
		this.planner = planner;
		this.action = action;
		this.observed = initial;
		this.delivered = initial;
		this.quietNanos = quiet.toNanos();
		this.executor = Executors.newSingleThreadScheduledExecutor((task) -> {
			Thread thread = new Thread(task, "reload-watch");
			thread.setDaemon(true);
			thread.setContextClassLoader(loader);
			return thread;
		});
		this.executor.scheduleWithFixedDelay(this::poll, interval.toMillis(), Math.max(1, interval.toMillis()),
				TimeUnit.MILLISECONDS);
	}

	private void poll() {
		try {
			ChangePlanner.Snapshot snapshot = this.planner.snapshot();
			if (!snapshot.equals(this.observed)) {
				this.observed = snapshot;
				this.changedAt = System.nanoTime();
			}
			if (!snapshot.equals(this.delivered) && System.nanoTime() - this.changedAt >= this.quietNanos) {
				this.delivered = snapshot;
				this.action.accept(snapshot);
			}
		}
		catch (RuntimeException | LinkageError ex) {
			logger.error("Cannot apply changed files; keeping the current application", ex);
		}
	}

	@Override
	public void close() { this.executor.shutdownNow(); }
}
