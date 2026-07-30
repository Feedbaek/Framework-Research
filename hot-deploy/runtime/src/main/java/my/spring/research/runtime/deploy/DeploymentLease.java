package my.spring.research.runtime.deploy;

import my.spring.research.runtime.api.BusinessHandler;

public final class DeploymentLease implements AutoCloseable {

	private final DeploymentSlot slot;
	private final ClassLoader originalContextClassLoader;
	private boolean closed;

	DeploymentLease(DeploymentSlot slot) {
		this.slot = slot;
		this.originalContextClassLoader = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(slot.candidate().classLoader());
	}

	public BusinessHandler handler() {
		return handler(BusinessHandler.class);
	}

	public <T> T handler(Class<T> handlerType) {
		return handlerType.cast(slot.candidate().handler());
	}

	public DeploymentSlot slot() {
		return slot;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			Thread.currentThread().setContextClassLoader(originalContextClassLoader);
			slot.releaseLease();
		}
	}
}
