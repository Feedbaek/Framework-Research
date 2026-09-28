package com.example.reload.generation;

import java.beans.Introspector;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ReflectionUtils;

/**
 * 세대 dispose 뒤 JVM 전역 캐시를 정리한다. devtools {@code Restarter.cleanupKnownCaches()}의 로직을
 * 옮긴 것이다.
 * <p>
 * {@link CachedIntrospectionResults}는 해당 세대의 클래스로더 항목만 지운다. 나머지 Spring 유틸 캐시와
 * Jackson {@code TypeFactory} 캐시는 클래스로더별로 지우는 API가 없어 전체를 비운다(다시 채워지는
 * 캐시이므로 기능에는 영향이 없다).
 */
public class GenerationCacheCleaner implements Consumer<ClassLoader> {

	private final ObjectProvider<ObjectMapper> parentObjectMapper;

	public GenerationCacheCleaner(ObjectProvider<ObjectMapper> parentObjectMapper) {
		this.parentObjectMapper = parentObjectMapper;
	}

	@Override
	public void accept(ClassLoader classLoader) {
		Introspector.flushCaches();
		ResolvableType.clearCache();
		ReflectionUtils.clearCache();
		AnnotationUtils.clearCache();
		CachedIntrospectionResults.clearClassLoader(classLoader);
		this.parentObjectMapper.ifAvailable((objectMapper) -> objectMapper.getTypeFactory().clearCache());
	}

}
