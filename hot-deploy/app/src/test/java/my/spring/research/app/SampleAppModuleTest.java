package my.spring.research.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import my.spring.research.runtime.api.AppModule;
import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.BusinessResponse;

class SampleAppModuleTest {

	@Test
	void exposesExactlyOneServiceLoaderModule() {
		List<AppModule> modules = ServiceLoader.load(AppModule.class)
				.stream()
				.map(ServiceLoader.Provider::get)
				.toList();

		assertEquals(1, modules.size());

		AppModule module = modules.getFirst();
		assertEquals("sample-app", module.metadata().appId());
		assertEquals(SampleBusinessHandler.class, module.entryPoint());
		assertEquals(List.of(SampleBusinessHandler.class), module.components());
	}

	@Test
	void handlesBusinessRequestWithConstructorInjectedRuntimePort() {
		SampleBusinessHandler handler = new SampleBusinessHandler(
				operation -> "runtime message for " + operation
		);

		BusinessResponse response = handler.handle(new BusinessRequest("hello"));

		assertEquals("OK", response.code());
		assertEquals("runtime message for hello", response.message());
		assertEquals("sample-app", response.attributes().get("appId"));
		assertEquals("1.0.0", response.attributes().get("appVersion"));
		assertEquals("hello", response.attributes().get("operation"));
	}
}
