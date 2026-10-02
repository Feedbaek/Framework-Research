package com.example.reload.generation;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import com.example.reload.ReloadProperties;
import com.example.reload.layout.ReloadLayout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProObject 22의 {@code @RefreshScope} Map bean처럼 같은 제네릭 타입의 scoped proxy가 여럿일 때, 세대의 의존성 해석이
 * 부모 bean 정의에 제네릭 정보가 없는 타입을 캐시해 부모의 이름 기반 선택을 바꾸면 안 된다.
 */
class ParentScopedGenericCandidateTest {

	@TempDir
	Path directory;

	@Test
	void generationDependencyResolutionDoesNotChangeParentCandidateSelection() {
		try (AnnotationConfigWebApplicationContext parent = new AnnotationConfigWebApplicationContext()) {
			parent.setServletContext(new MockServletContext());
			parent.addBeanFactoryPostProcessor((factory) -> factory.registerScope("refresh", new SimpleThreadScope()));
			parent.register(Parent.class);
			parent.refresh();
			ReloadProperties properties = new ReloadProperties();
			properties.setClasspath(List.of(this.directory.toString()));
			ReloadLayout layout = ReloadLayout.resolve(properties, new DefaultListableBeanFactory());
			try (AnnotationConfigWebApplicationContext child = new GenerationApplicationContext(layout)) {
				child.setParent(parent);
				child.setServletContext(parent.getServletContext());
				child.register(Child.class);
				child.refresh();
				assertThat(child.getBean("generationBean", List.class)).hasSize(2);
			}
			assertThat(parent.getBean("serviceBlockManager", Holder.class).flags()).containsKey("inherited");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Parent {

		@Bean
		@Scope(scopeName = "refresh", proxyMode = ScopedProxyMode.TARGET_CLASS)
		Map<String, Boolean> enableInheritedServiceBlockMap() {
			return new HashMap<>(Map.of("inherited", true));
		}

		@Bean
		@Scope(scopeName = "refresh", proxyMode = ScopedProxyMode.TARGET_CLASS)
		Map<String, Boolean> printExceptionStackTracePropertyMap() {
			return new HashMap<>(Map.of("print", true));
		}

		/** 부모 기동 중 해석되어 printExceptionStackTracePropertyMap의 제네릭 타입이 먼저 캐시된다. */
		@Bean
		Holder bodyParserCreator(Map<String, Boolean> printExceptionStackTracePropertyMap) {
			return new Holder(printExceptionStackTracePropertyMap);
		}

		/** 첫 요청 때 생성되는 부모 bean. 세대가 먼저 뜬 뒤에 의존성을 해석한다. */
		@Bean
		@Scope(scopeName = "refresh", proxyMode = ScopedProxyMode.TARGET_CLASS)
		Holder serviceBlockManager(Map<String, Boolean> enableInheritedServiceBlockMap) {
			return new Holder(enableInheritedServiceBlockMap);
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Child {

		/** 이름으로 bean을 특정하지 않는 Map 의존성. 해석하면서 부모의 Map 후보를 모두 검사한다. */
		@Bean
		List<Map<String, Boolean>> generationBean(List<Map<String, Boolean>> flags) {
			return flags;
		}

	}

	static class Holder {

		private final Map<String, Boolean> flags;

		Holder(Map<String, Boolean> flags) {
			this.flags = flags;
		}

		Map<String, Boolean> flags() {
			return this.flags;
		}

	}

}
