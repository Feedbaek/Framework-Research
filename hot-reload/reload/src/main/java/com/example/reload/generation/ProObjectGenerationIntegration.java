package com.example.reload.generation;

import java.util.List;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * ProObject 22의 실행 빈만 세대에 다시 만든다. 원본 starter와 인프라 빈은 변경하지 않는다.
 * @Bean 정의를 복제하므로 factory method의 ApplicationContext/클래스로더 의존성은 자식에서 해석된다.
 * 빈 인스턴스나 부모에 주입된 의존성을 복사하지 않는다.
 */
public final class ProObjectGenerationIntegration implements GenerationIntegration {
	private static final List<String> EXECUTION_BEANS = List.of(
			"beanClassLoaderHolder", "objectFactory", "bodyParserCreator", "componentApplicationContext",
			"traceLogInterceptor", "prePostErrorProcessInterceptor", "imageLogManager", "serviceAspect",
			"handlerMethodCommonInterceptor", "springControllerExceptionHandler", "proObjectDispatcher",
			"proObjectServiceHandler", "httpServiceHandler", "proObjectHttpServiceHandler", "http2ServiceHandler",
			"webtServiceHandler", "serviceManager");

	private final ConfigurableListableBeanFactory parent;

	public ProObjectGenerationIntegration(ConfigurableListableBeanFactory parent) { this.parent = parent; }

	@Override
	public void configure(AnnotationConfigWebApplicationContext generation) {
		generation.addBeanFactoryPostProcessor((factory) -> {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
			for (String name : EXECUTION_BEANS) {
				String source = this.parent.containsBeanDefinition("scopedTarget." + name) ? "scopedTarget." + name : name;
				if (!this.parent.containsBeanDefinition(source)) { continue; }
				BeanDefinition original = this.parent.getMergedBeanDefinition(source);
				if (!(original instanceof RootBeanDefinition root) || original.getFactoryMethodName() == null) {
					throw new IllegalStateException("Unsupported ProObject bean definition: " + source);
				}
				if (registry.containsBeanDefinition(name)) {
					throw new IllegalStateException("Generation already defines ProObject execution bean: " + name);
				}
				RootBeanDefinition definition = root.cloneBeanDefinition();
				definition.setScope(BeanDefinition.SCOPE_SINGLETON);
				definition.setLazyInit(false);
				registry.registerBeanDefinition(name, definition);
			}
		});
	}

	@Override
	public void validate(AnnotationConfigWebApplicationContext generation) {
		for (String name : List.of("beanClassLoaderHolder", "objectFactory", "proObjectDispatcher")) {
			if (!generation.getBeanFactory().containsLocalBean(name)) {
				throw new IllegalStateException("ProObject generation is missing " + name);
			}
		}
		RequestMappingHandlerAdapter adapter = generation.getBean("requestMappingHandlerAdapter", RequestMappingHandlerAdapter.class);
		boolean proObject = false;
		for (Class<?> type = adapter.getClass(); type != null; type = type.getSuperclass()) {
			proObject |= type.getName().equals("com.tmax.proobject.service.mvc.http.ProObjectHttpHandlerAdapter");
		}
		if (!proObject) {
			throw new IllegalStateException("ProObject requires its HTTP handler adapter in each generation");
		}
	}
}
