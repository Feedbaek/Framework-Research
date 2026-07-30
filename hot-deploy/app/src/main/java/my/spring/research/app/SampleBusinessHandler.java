package my.spring.research.app;

import java.util.Map;

import my.spring.research.runtime.api.BusinessHandler;
import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.BusinessResponse;
import my.spring.research.runtime.api.RuntimeMessageProvider;

public final class SampleBusinessHandler implements BusinessHandler {
	private final RuntimeMessageProvider messageProvider;

	public SampleBusinessHandler(RuntimeMessageProvider messageProvider) {
		this.messageProvider = messageProvider;
	}

	@Override
	public BusinessResponse handle(BusinessRequest request) {
		return new BusinessResponse(
				"OK",
				messageProvider.messageFor(request.operation()),
				Map.of(
						"appId", "sample-app",
						"appVersion", "1.0.0",
						"operation", request.operation()
				)
		);
	}
}
