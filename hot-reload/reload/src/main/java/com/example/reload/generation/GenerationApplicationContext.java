package com.example.reload.generation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
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

	private final Map<String, Long> phases = new LinkedHashMap<>();

	GenerationApplicationContext(ReloadLayout layout) {
		this.layout = layout;
	}

	/**
	 * refresh 단계별 소요 시간. 세대 교체가 느릴 때 어느 단계인지 로그로 좁히기 위한 것이다.
	 */
	String phases() {
		return this.phases.toString();
	}

	private void record(String phase, long startNanos) {
		this.phases.merge(phase, (System.nanoTime() - startNanos) / 1_000_000, Long::sum);
	}

	@Override
	protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
		long start = System.nanoTime();
		try {
			super.loadBeanDefinitions(beanFactory);
		}
		finally {
			record("scan", start);
		}
	}

	@Override
	protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		long start = System.nanoTime();
		try {
			super.invokeBeanFactoryPostProcessors(beanFactory);
		}
		finally {
			record("factoryPostProcessors", start);
		}
	}

	@Override
	protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
		long start = System.nanoTime();
		try {
			super.registerBeanPostProcessors(beanFactory);
		}
		finally {
			record("beanPostProcessors", start);
		}
	}

	@Override
	protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
		long start = System.nanoTime();
		try {
			super.finishBeanFactoryInitialization(beanFactory);
		}
		finally {
			record("singletons", start);
		}
	}

	@Override
	protected void customizeBeanFactory(DefaultListableBeanFactory beanFactory) {
		super.customizeBeanFactory(beanFactory);
		// 부모 후보의 판단은 부모 resolver에 맡겨 부모 bean definition의 타입 캐시를 오염시키지 않는다.
		beanFactory.setAutowireCandidateResolver(new GenerationAutowireCandidateResolver());
	}

	/**
	 * 이 세대 자신의 lifecycle 이벤트({@code ContextRefreshedEvent}, {@code ContextClosedEvent} 등)는 부모로 보내지
	 * 않는다. 부모에는 출처를 확인하지 않고 이를 애플리케이션 전체의 기동·종료로 처리하는 리스너가 있다(예: JEUS
	 * starter의 {@code JEUSFinalizer}는 {@code ContextClosedEvent}에 TM 서버를 내려 HTTP와 함께 쓰는 리스너를 닫고,
	 * ProObject는 master 등록을 해제한다). 업무 코드가 발행한 이벤트는 그대로 부모에도 전달한다.
	 */
	@Override
	protected void publishEvent(Object event, @Nullable ResolvableType typeHint) {
		if (event instanceof ApplicationContextEvent contextEvent && contextEvent.getApplicationContext() == this) {
			// lifecycle 이벤트는 multicaster 초기화 뒤, 종료 시에는 bean 파괴 전에 발행되므로 singleton이 있다.
			getBeanFactory().getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class)
				.multicastEvent(contextEvent, typeHint);
			return;
		}
		super.publishEvent(event, typeHint);
	}

	@Override
	protected ClassPathBeanDefinitionScanner getClassPathBeanDefinitionScanner(DefaultListableBeanFactory beanFactory) {
		ClassPathBeanDefinitionScanner scanner = super.getClassPathBeanDefinitionScanner(beanFactory);
		scanner.addExcludeFilter(
				(metadataReader, metadataReaderFactory) -> !this.layout.isChildComponent(metadataReader));
		return scanner;
	}

}
