package com.example.reload.generation;

import org.springframework.core.Ordered;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * 부모에서 발견하고 자식 세대마다 실행하는 라이브러리 연동 계약.
 * 구현은 부모 빈이므로 전달받은 context/Class/인스턴스를 필드에 보관하면 안 된다.
 * 부모의 BeanPostProcessor를 복사하지 않고 자식용 설정을 register한다.
 */
public interface GenerationIntegration extends Ordered {
	default void configure(AnnotationConfigWebApplicationContext generation) { }
	/** refresh와 DispatcherServlet 초기화 후, 공개하기 전에 검증한다. 실패 시 기존 세대를 유지한다. */
	default void validate(AnnotationConfigWebApplicationContext generation) { }
	@Override
	default int getOrder() { return 0; }
}
