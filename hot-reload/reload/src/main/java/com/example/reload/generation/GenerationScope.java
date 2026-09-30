package com.example.reload.generation;

import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** 동기 호출 체인에 고정된 세대. 다른 스레드에는 자동 전파하지 않는다. */
public final class GenerationScope implements AutoCloseable {
	private static final ThreadLocal<AnnotationConfigWebApplicationContext> CURRENT = new ThreadLocal<>();
	private final AnnotationConfigWebApplicationContext previous;

	private GenerationScope(AnnotationConfigWebApplicationContext context) {
		this.previous = CURRENT.get();
		CURRENT.set(context);
	}

	public static GenerationScope enter(AnnotationConfigWebApplicationContext context) {
		return new GenerationScope(context);
	}

	public static AnnotationConfigWebApplicationContext current() { return CURRENT.get(); }

	@Override
	public void close() {
		if (this.previous == null) { CURRENT.remove(); }
		else { CURRENT.set(this.previous); }
	}
}
