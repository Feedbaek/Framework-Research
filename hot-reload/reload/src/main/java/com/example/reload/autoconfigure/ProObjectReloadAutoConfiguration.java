package com.example.reload.autoconfigure;

import java.lang.reflect.InvocationTargetException;

import com.example.reload.generation.GenerationIntegration;
import com.example.reload.generation.GenerationManager;
import com.example.reload.generation.ProObjectGenerationIntegration;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/** ProObject starter의 존재 여부만 검사하므로 엔진에 ProObject 의존성을 추가하지 않는다. */
@AutoConfiguration(after = ReloadAutoConfiguration.class,
		afterName = "com.tmax.proobject.autoconfigure.service.ProObjectServiceAutoConfiguration")
@ConditionalOnClass(name = "com.tmax.proobject.service.mvc.http.ProObjectHttpHandlerAdapter")
@ConditionalOnProperty(prefix = "reload", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional(OnHybridCondition.class)
public class ProObjectReloadAutoConfiguration {
	@Bean
	GenerationIntegration proObjectGenerationIntegration(ConfigurableListableBeanFactory factory) {
		return new ProObjectGenerationIntegration(factory);
	}

	@Bean
	static BeanPostProcessor proObjectGenerationDispatcherBridge(ConfigurableListableBeanFactory factory) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String name) {
				if (!name.equals("proObjectDispatcher")) { return bean; }
				ProxyFactory proxy = new ProxyFactory(bean);
				proxy.setProxyTargetClass(true);
				proxy.addAdvice((MethodInterceptor) (invocation) -> {
					if (!invocation.getMethod().getName().equals("dispatch")) { return invocation.proceed(); }
					if (!factory.containsSingleton("generationManager")) { return invocation.proceed(); }
					GenerationManager manager = factory.getBean("generationManager", GenerationManager.class);
					if (manager.currentGenerationId() == 0) { return invocation.proceed(); }
					return manager.withGeneration((generation) -> {
						Object target = generation.getBean("proObjectDispatcher");
						try {
							return invocation.getMethod().invoke(target, invocation.getArguments());
						}
						catch (InvocationTargetException ex) { throw ex.getCause(); }
					});
				});
				return proxy.getProxy();
			}
		};
	}
}
