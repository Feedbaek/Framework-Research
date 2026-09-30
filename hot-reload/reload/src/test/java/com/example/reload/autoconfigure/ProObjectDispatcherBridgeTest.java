package com.example.reload.autoconfigure;

import com.example.reload.generation.GenerationManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProObjectDispatcherBridgeTest {

	@Test
	void parentDispatchUsesPinnedGenerationButOtherMethodsStayOnParent() throws Throwable {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		GenerationManager manager = mock(GenerationManager.class);
		factory.registerSingleton("generationManager", manager);
		AnnotationConfigWebApplicationContext child = mock(AnnotationConfigWebApplicationContext.class);
		when(child.getBean("proObjectDispatcher")).thenReturn(new Dispatcher("child"));
		when(manager.currentGenerationId()).thenReturn(1);
		when(manager.withGeneration(any())).thenAnswer((call) -> {
			GenerationManager.GenerationOperation<?> operation = call.getArgument(0);
			return operation.invoke(child);
		});
		var processor = ProObjectReloadAutoConfiguration.proObjectGenerationDispatcherBridge(factory);
		Dispatcher proxy = (Dispatcher) processor.postProcessAfterInitialization(new Dispatcher("parent"), "proObjectDispatcher");
		assertThat(proxy.dispatch("request")).isEqualTo("child:request");
		assertThat(proxy.owner()).isEqualTo("parent");
		verify(manager).withGeneration(any());
		when(manager.currentGenerationId()).thenReturn(0);
		assertThat(proxy.dispatch("startup")).isEqualTo("parent:startup");
	}

	public static class Dispatcher {
		private final String owner;
		public Dispatcher(String owner) { this.owner = owner; }
		public String dispatch(String request) { return this.owner + ":" + request; }
		public String owner() { return this.owner; }
	}
}
