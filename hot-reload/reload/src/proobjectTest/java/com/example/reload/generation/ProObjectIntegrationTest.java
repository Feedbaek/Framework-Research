package com.example.reload.generation;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import com.example.reload.child.ChildWebMvcConfig;
import com.tmax.proobject.autoconfigure.service.ProObjectWebMvcConfiguration;
import com.tmax.proobject.model.servicegroup.ServiceMeta;
import com.tmax.proobject.runtime.application.ApplicationManager;
import com.tmax.proobject.runtime.system.application.BuiltinApplicationManager;
import com.tmax.proobject.runtime.application.servicegroup.ServiceGroupManager;
import com.tmax.proobject.runtime.context.ServiceContextHolder;
import com.tmax.proobject.runtime.encryption.EncryptionManager;
import com.tmax.proobject.runtime.event.EventManager;
import com.tmax.proobject.runtime.meta.MetaRepository;
import com.tmax.proobject.runtime.node.NodeAddressManager;
import com.tmax.proobject.runtime.util.BeanClassLoaderHolder;
import com.tmax.proobject.runtime.util.ObjectFactory;
import com.tmax.proobject.service.intercept.ServiceRequestHandlingCommonInterceptor;
import com.tmax.proobject.service.mvc.ProObjectDispatcher;
import com.tmax.proobject.service.mvc.http.ProObjectHttpHandlerAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 로컬 ProObject 22 아티팩트의 팩터리·MVC 계약을 검증한다. JEUS/외부 시스템은 기동하지 않는다. */
class ProObjectIntegrationTest {
	@Test
	void eachGenerationGetsItsOwnFactoriesAndRealProObjectRequestPreparation() throws Exception {
		try (AnnotationConfigWebApplicationContext parent = new AnnotationConfigWebApplicationContext()) {
			parent.setServletContext(new MockServletContext());
			parent.register(Parent.class);
			parent.refresh();
			com.tmax.proobject.logger.ProObjectLoggerManager.setApplicationName("app");
			ProObjectGenerationIntegration integration = new ProObjectGenerationIntegration(parent.getBeanFactory());
			Object parentFactory = parent.getBean("objectFactory");
			for (int i = 0; i < 2; i++) {
				try (URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader());
						AnnotationConfigWebApplicationContext child = new AnnotationConfigWebApplicationContext()) {
					child.setParent(parent);
					child.setServletContext(parent.getServletContext());
					child.setClassLoader(loader);
					child.register(ChildWebMvcConfig.class, Endpoint.class);
					integration.configure(child);
					child.refresh();
					integration.validate(child);
					assertThat(child.getBean("objectFactory")).isNotSameAs(parentFactory);
					assertThat(ReflectionTestUtils.getField(child.getBean("objectFactory"), "applicationContext")).isSameAs(child);
					assertThat(child.getBean(BeanClassLoaderHolder.class).get()).isSameAs(loader);
					assertThat(ReflectionTestUtils.getField(child.getBean("proObjectDispatcher"), "applicationContext")).isSameAs(child);
					assertThat(child.getBean("requestMappingHandlerAdapter")).isInstanceOf(ProObjectHttpHandlerAdapter.class);
					MockMvcBuilders.webAppContextSetup(child).build().perform(get("/po"))
							.andExpect(status().isOk()).andExpect(content().string("app.group.service:true"));
					assertThat(ServiceContextHolder.hasServiceContext()).isFalse();
				}
			}
		}
	}

	@Configuration
	static class Parent extends ProObjectWebMvcConfiguration {
		@Bean BeanClassLoaderHolder beanClassLoaderHolder() { return new BeanClassLoaderHolder(); }
		@Bean EventManager eventManager() {
			EventManager events = mock(EventManager.class);
			when(events.generateGUID()).thenReturn(new com.tmax.proobject.core2.util.consistency.GUID("test-guid"));
			return events;
		}
		@Bean EncryptionManager encryptionManager() { return mock(EncryptionManager.class); }
		@Bean MetaRepository metaRepository() { return mock(MetaRepository.class); }
		@Bean ServiceRequestHandlingCommonInterceptor handlerMethodCommonInterceptor() {
			return mock(ServiceRequestHandlingCommonInterceptor.class);
		}
		@Bean ApplicationManager applicationManager() throws Exception {
			ApplicationManager manager = mock(ApplicationManager.class);
			ServiceGroupManager group = mock(ServiceGroupManager.class);
			ServiceMeta meta = new ServiceMeta();
			meta.setServiceName("service");
			when(group.getServiceMetaByURL("/po")).thenReturn(meta);
			when(group.getServiceGroupName()).thenReturn("group");
			when(manager.getApplicationName()).thenReturn("app");
			when(manager.getServiceGroupManagers()).thenReturn(Map.of("group", group));
			return manager;
		}
		@Bean ObjectFactory objectFactory(ApplicationContext context, ApplicationManager applicationManager,
				BeanClassLoaderHolder holder) {
			return new ObjectFactory(context, applicationManager, mock(BuiltinApplicationManager.class),
					mock(NodeAddressManager.class), Map.of(), holder);
		}
		@Bean ProObjectDispatcher proObjectDispatcher(EventManager events, BeanClassLoaderHolder holder) {
			return new ProObjectDispatcher(events, holder);
		}
		// HttpProtocolParserUtil의 이벤트 외부 연동은 이 테스트의 범위 밖이다.
	}

	@RestController
	static class Endpoint {
		@GetMapping("/po")
		String call() {
			var context = ServiceContextHolder.getServiceContext();
			return context.getServiceName().getFullName() + ":" + (context.getRequestContext() != null);
		}
	}
}
