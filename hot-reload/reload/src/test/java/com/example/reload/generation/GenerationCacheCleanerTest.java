package com.example.reload.generation;

import java.lang.reflect.Field;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 clear API가 없어 리플렉션으로 비우는 Spring 내부 캐시. Spring을 올려 클래스나 필드 이름이 바뀌면
 * {@link GenerationCacheCleaner}는 조용히 건너뛰므로, 여기서 먼저 실패하게 한다.
 */
class GenerationCacheCleanerTest {

	@ParameterizedTest
	@CsvSource({ "org.springframework.context.annotation.BeanAnnotationHelper, beanNameCache",
			"org.springframework.context.annotation.BeanAnnotationHelper, scopedProxyCache",
			"org.springframework.core.BridgeMethodResolver, cache",
			"org.springframework.security.core.annotation.SecurityAnnotationScanners, uniqueScanners",
			"org.springframework.security.core.annotation.SecurityAnnotationScanners, uniqueTemplateScanners",
			"org.springframework.security.core.annotation.SecurityAnnotationScanners, uniqueTypesScanners" })
	@SuppressWarnings("unchecked")
	void clearsSpringInternalCacheWithoutPublicApi(String className, String fieldName) throws Exception {
		Field field = ReflectionUtils.findField(Class.forName(className), fieldName);
		assertThat(field).as("%s.%s (Spring internal cache)", className, fieldName).isNotNull();
		ReflectionUtils.makeAccessible(field);
		Map<Object, Object> cache = (Map<Object, Object>) ReflectionUtils.getField(field, null);
		assertThat(cache).isNotNull();
		Object key = new Object();
		// 값 타입은 캐시마다 다르지만 비워지는지만 보므로 아무 값이나 넣는다.
		cache.put(key, "value");

		new GenerationCacheCleaner(new StaticListableBeanFactory().getBeanProvider(ObjectMapper.class))
			.accept(getClass().getClassLoader());

		assertThat(cache).doesNotContainKey(key);
	}

}
