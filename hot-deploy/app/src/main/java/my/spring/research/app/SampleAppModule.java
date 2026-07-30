package my.spring.research.app;

import java.util.List;

import my.spring.research.runtime.api.AppMetadata;
import my.spring.research.runtime.api.AppModule;
import my.spring.research.runtime.api.BusinessHandler;

public final class SampleAppModule implements AppModule {
	private static final AppMetadata METADATA = new AppMetadata("sample-app", "1.0.0", "[0.0.1,1.0.0)");

	@Override
	public AppMetadata metadata() {
		return METADATA;
	}

	@Override
	public List<Class<?>> components() {
		return List.of(SampleBusinessHandler.class);
	}

	@Override
	public Class<? extends BusinessHandler> entryPoint() {
		return SampleBusinessHandler.class;
	}
}
