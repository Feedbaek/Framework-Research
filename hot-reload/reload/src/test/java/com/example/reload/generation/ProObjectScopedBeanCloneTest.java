package com.example.reload.generation;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProObject 22의 {@code @RefreshScope} 실행 빈(예: prePostErrorProcessInterceptor)은 부모에 scoped proxy와
 * autowire 후보가 아닌 {@code scopedTarget.*} 정의로 등록된다. 세대에 복제한 빈은 타입 주입이 가능해야 한다.
 */
class ProObjectScopedBeanCloneTest {

	@Test
	void clonedScopedTargetIsAutowireCandidateInGeneration() {
		try (AnnotationConfigWebApplicationContext parent = new AnnotationConfigWebApplicationContext()) {
			parent.setServletContext(new MockServletContext());
			parent.addBeanFactoryPostProcessor((factory) -> factory.registerScope("refresh", new SimpleThreadScope()));
			parent.register(Parent.class);
			parent.refresh();
			try (AnnotationConfigWebApplicationContext child = new AnnotationConfigWebApplicationContext()) {
				child.setParent(parent);
				child.setServletContext(parent.getServletContext());
				new ProObjectGenerationIntegration(parent.getBeanFactory()).configure(child);
				child.refresh();
				Interceptor interceptor = child.getBean("prePostErrorProcessInterceptor", Interceptor.class);
				assertThat(child.getBeanFactory().containsLocalBean("prePostErrorProcessInterceptor")).isTrue();
				assertThat(child.getBean("serviceAspect", Aspect.class).interceptor).isSameAs(interceptor);
			}
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Parent {

		@Bean
		@Scope(scopeName = "refresh", proxyMode = ScopedProxyMode.TARGET_CLASS)
		Interceptor prePostErrorProcessInterceptor() {
			return new Interceptor();
		}

		@Bean
		Aspect serviceAspect(Interceptor prePostErrorProcessInterceptor) {
			return new Aspect(prePostErrorProcessInterceptor);
		}

	}

	static class Interceptor {

	}

	static class Aspect {

		final Interceptor interceptor;

		Aspect(Interceptor interceptor) {
			this.interceptor = interceptor;
		}

	}

}
