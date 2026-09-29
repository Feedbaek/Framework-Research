package com.example.reload.generation;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import com.example.reload.layout.ReloadLayout;

/**
 * 자식 세대의 context. 컴포넌트 스캔에서 자식 세대가 등록할 클래스
 * ({@link ReloadLayout#isChildComponent})만 남긴다.
 * <p>
 * 부모 소유 클래스(공유 타입, 부모 bean)는 이미 부모에 등록돼 있다. {@code @SpringBootApplication} 클래스가
 * 자식에 등록되면 자식에서 auto-configuration과 컴포넌트 스캔이 다시 일어나므로 반드시 제외한다. 세대
 * 클래스로더는 부모 클래스패스의 리소스도 돌려주므로, 스캔 패키지와 겹치는 라이브러리 jar의 컴포넌트와
 * auto-configuration도 자식 클래스패스 밖이라는 이유로 제외한다(부모에만 등록된다).
 */
class GenerationApplicationContext extends AnnotationConfigWebApplicationContext {

	private final ReloadLayout layout;

	GenerationApplicationContext(ReloadLayout layout) {
		this.layout = layout;
	}

	@Override
	protected ClassPathBeanDefinitionScanner getClassPathBeanDefinitionScanner(DefaultListableBeanFactory beanFactory) {
		ClassPathBeanDefinitionScanner scanner = super.getClassPathBeanDefinitionScanner(beanFactory);
		scanner.addExcludeFilter(
				(metadataReader, metadataReaderFactory) -> !this.layout.isChildComponent(metadataReader));
		return scanner;
	}

}
