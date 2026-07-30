package my.spring.research.runtime.loader;

import java.net.URLClassLoader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.support.GenericApplicationContext;

import my.spring.research.runtime.api.AppMetadata;
import my.spring.research.runtime.api.BusinessHandler;

public final class LoadedCandidate implements AutoCloseable {

	private final AppArtifact artifact;
	private final AppMetadata metadata;
	private final GenericApplicationContext context;
	private final URLClassLoader classLoader;
	private final BusinessHandler handler;
	private final AtomicBoolean closed = new AtomicBoolean();

	public LoadedCandidate(
			AppArtifact artifact,
			AppMetadata metadata,
			GenericApplicationContext context,
			URLClassLoader classLoader,
			BusinessHandler handler
	) {
		this.artifact = artifact;
		this.metadata = metadata;
		this.context = context;
		this.classLoader = classLoader;
		this.handler = handler;
	}

	public AppArtifact artifact() {
		return artifact;
	}

	public AppMetadata metadata() {
		return metadata;
	}

	public GenericApplicationContext context() {
		return context;
	}

	public URLClassLoader classLoader() {
		return classLoader;
	}

	public BusinessHandler handler() {
		return handler;
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		RuntimeException failure = null;
		try {
			context.close();
		}
		catch (RuntimeException ex) {
			failure = ex;
		}
		try {
			classLoader.close();
		}
		catch (IOException ex) {
			IllegalStateException closeFailure = new IllegalStateException("Failed to close app classloader", ex);
			if (failure == null) {
				failure = closeFailure;
			}
			else {
				failure.addSuppressed(closeFailure);
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
