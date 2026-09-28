package com.example.reload.generation;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import com.example.reload.layout.ClassOwnership;

/**
 * 자식 세대의 context. 컴포넌트 스캔에서 부모 소유 클래스와 애플리케이션 클래스를 뺀다.
 * <p>
 * 부모 소유 클래스(공유 타입, 부모 bean)는 이미 부모에 등록돼 있다. {@code @SpringBootApplication} 클래스가
 * 자식에 등록되면 자식에서 auto-configuration과 컴포넌트 스캔이 다시 일어나므로 반드시 제외한다.
 */
class GenerationApplicationContext extends AnnotationConfigWebApplicationContext {

	private final ClassOwnership ownership;

	GenerationApplicationContext(ClassOwnership ownership) {
		this.ownership = ownership;
	}

	@Override
	protected ClassPathBeanDefinitionScanner getClassPathBeanDefinitionScanner(DefaultListableBeanFactory beanFactory) {
		ClassPathBeanDefinitionScanner scanner = super.getClassPathBeanDefinitionScanner(beanFactory);
		scanner.addExcludeFilter((metadataReader, metadataReaderFactory) -> this.ownership
			.isParentOwned(metadataReader.getClassMetadata().getClassName())
				|| metadataReader.getAnnotationMetadata().isAnnotated(SpringBootConfiguration.class.getName()));
		return scanner;
	}

}
