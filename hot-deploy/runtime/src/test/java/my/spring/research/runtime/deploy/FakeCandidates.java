package my.spring.research.runtime.deploy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.support.GenericApplicationContext;

import my.spring.research.runtime.api.AppMetadata;
import my.spring.research.runtime.api.BusinessHandler;
import my.spring.research.runtime.api.BusinessResponse;
import my.spring.research.runtime.loader.AppArtifact;
import my.spring.research.runtime.loader.LoadedCandidate;

final class FakeCandidates {
	private FakeCandidates() {
	}

	static LoadedCandidate candidate(String version) {
		return candidate(version, new AtomicBoolean());
	}

	static LoadedCandidate candidate(String version, AtomicBoolean closed) {
		BusinessHandler handler = request -> BusinessResponse.ok(version + ":" + request.operation());
		return candidate(version, closed, handler);
	}

	static LoadedCandidate candidate(String version, AtomicBoolean closed, BusinessHandler handler) {
		GenericApplicationContext context = new GenericApplicationContext() {
			@Override
			public void close() {
				closed.set(true);
				super.close();
			}
		};
		context.refresh();
		URLClassLoader classLoader = new URLClassLoader(new URL[0], ClassLoader.getSystemClassLoader());
		return new LoadedCandidate(
				new AppArtifact(Path.of("app-" + version + ".jar"), "sha-" + version, 1024),
				new AppMetadata("sample-app", version, "[0.0.1,1.0.0)"),
				context,
				classLoader,
				handler);
	}
}
