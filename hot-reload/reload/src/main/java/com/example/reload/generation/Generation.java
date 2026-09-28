package com.example.reload.generation;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import com.example.reload.devtools.restart.classloader.RestartClassLoader;

/**
 * 자식 클래스로더 + 자식 context + 자식 {@link DispatcherServlet} 한 벌.
 * <p>
 * 진행 중 요청 수({@code inFlight})를 세고, retire된 뒤 마지막 요청이 끝나면 스스로 dispose한다.
 * dispose 뒤에는 클래스로더·context·dispatcher 참조를 모두 놓아서 이 객체가 남아 있더라도
 * 자식 클래스로더가 수거될 수 있게 한다.
 */
final class Generation {

	private static final Log logger = LogFactory.getLog(Generation.class);

	private final int id;

	private final AtomicInteger inFlight = new AtomicInteger();

	private final AtomicBoolean disposed = new AtomicBoolean();

	private final Consumer<ClassLoader> cacheCleaner;

	private volatile RestartClassLoader classLoader;

	private volatile AnnotationConfigWebApplicationContext context;

	private volatile DispatcherServlet dispatcher;

	private volatile boolean retired;

	private volatile ScheduledFuture<?> forcedDisposal;

	Generation(int id, RestartClassLoader classLoader, Consumer<ClassLoader> cacheCleaner) {
		this.id = id;
		this.classLoader = classLoader;
		this.cacheCleaner = cacheCleaner;
	}

	int id() {
		return this.id;
	}

	RestartClassLoader classLoader() {
		return this.classLoader;
	}

	AnnotationConfigWebApplicationContext context() {
		return this.context;
	}

	void setContext(AnnotationConfigWebApplicationContext context) {
		this.context = context;
	}

	DispatcherServlet dispatcher() {
		return this.dispatcher;
	}

	void setDispatcher(DispatcherServlet dispatcher) {
		this.dispatcher = dispatcher;
	}

	int inFlight() {
		return this.inFlight.get();
	}

	boolean isRetired() {
		return this.retired;
	}

	boolean isDisposed() {
		return this.disposed.get();
	}

	/**
	 * 요청 하나를 이 세대에 등록한다. retire된 세대면 {@code false}.
	 * <p>
	 * 먼저 증가시키고 나서 {@code retired}를 확인한다. 반대 순서면 "확인 → retire → 즉시 dispose
	 * → 증가" 순서로 끼어들어 dispose된 세대로 요청이 들어갈 수 있다.
	 */
	boolean acquire() {
		this.inFlight.incrementAndGet();
		if (this.retired) {
			release();
			return false;
		}
		return true;
	}

	void release() {
		if (this.inFlight.decrementAndGet() == 0 && this.retired) {
			dispose();
		}
	}

	/**
	 * 새 요청을 받지 않도록 표시한다. 진행 중 요청이 없으면 바로 dispose하고, 있으면 마지막
	 * 요청이 끝날 때 dispose된다. {@code drainTimeout}이 지나도 끝나지 않으면 강제로 dispose한다.
	 */
	void retire(ScheduledExecutorService scheduler, Duration drainTimeout) {
		this.retired = true;
		if (this.inFlight.get() == 0) {
			dispose();
			return;
		}
		logger.info("Generation " + this.id + " retired with " + this.inFlight.get()
				+ " in-flight request(s); draining for up to " + drainTimeout);
		this.forcedDisposal = scheduler.schedule(this::forceDispose, drainTimeout.toMillis(), TimeUnit.MILLISECONDS);
		if (isDisposed()) {
			// 예약하는 사이에 마지막 요청이 끝나 이미 dispose됨
			this.forcedDisposal.cancel(false);
		}
	}

	private void forceDispose() {
		if (!isDisposed()) {
			logger.warn("Generation " + this.id + " did not drain in time; disposing with " + this.inFlight.get()
					+ " request(s) still in flight");
			dispose();
		}
	}

	/**
	 * dispatcher 종료 → context 종료 → 캐시 정리 → 클래스로더 참조 해제. 한 번만 실행된다.
	 */
	void dispose() {
		if (!this.disposed.compareAndSet(false, true)) {
			return;
		}
		ScheduledFuture<?> forced = this.forcedDisposal;
		if (forced != null) {
			forced.cancel(false);
			this.forcedDisposal = null;
		}
		RestartClassLoader loader = this.classLoader;
		Thread thread = Thread.currentThread();
		ClassLoader previousTccl = thread.getContextClassLoader();
		if (loader != null) {
			thread.setContextClassLoader(loader);
		}
		try {
			DispatcherServlet dispatcher = this.dispatcher;
			if (dispatcher != null) {
				try {
					dispatcher.destroy();
				}
				catch (RuntimeException ex) {
					logger.warn("Failed to destroy dispatcher of generation " + this.id, ex);
				}
			}
			AnnotationConfigWebApplicationContext context = this.context;
			if (context != null) {
				try {
					context.close();
				}
				catch (RuntimeException ex) {
					logger.warn("Failed to close context of generation " + this.id, ex);
				}
			}
		}
		finally {
			thread.setContextClassLoader(previousTccl);
		}
		this.dispatcher = null;
		this.context = null;
		if (loader != null) {
			try {
				this.cacheCleaner.accept(loader);
			}
			catch (RuntimeException ex) {
				logger.warn("Failed to clean caches for generation " + this.id, ex);
			}
			try {
				// jar 파일 핸들을 닫는다(Windows 파일 잠금 방지). 이후 이 로더로 새 클래스는 로드할 수 없다.
				loader.close();
			}
			catch (IOException ex) {
				logger.debug("Failed to close class loader of generation " + this.id, ex);
			}
		}
		this.classLoader = null;
		logger.info("Generation " + this.id + " disposed");
	}

	@Override
	public String toString() {
		return "Generation[" + this.id + (this.retired ? ", retired" : "") + (isDisposed() ? ", disposed" : "") + "]";
	}

}
