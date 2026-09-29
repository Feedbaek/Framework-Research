package com.example.reload.generation;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * 세대 dispose 뒤 JVM 전역 캐시를 정리한다. devtools {@code Restarter.cleanupKnownCaches()}의 로직을
 * 옮긴 것에 devtools가 다루지 않는 캐시 몇 개를 더했다.
 * <p>
 * {@link CachedIntrospectionResults}는 해당 세대의 클래스로더 항목만 지운다. 나머지 Spring 유틸 캐시와
 * Jackson {@code TypeFactory} 캐시는 클래스로더별로 지우는 API가 없어 전체를 비운다(다시 채워지는
 * 캐시이므로 기능에는 영향이 없다).
 * <p>
 * 공개 clear API가 없는 Spring 내부 정적 캐시는 리플렉션으로 비운다. 클래스가 없으면(해당 라이브러리가 없거나
 * 버전이 다르면) 건너뛴다.
 * <ul>
 * <li>{@code BeanAnnotationHelper.beanNameCache}/{@code scopedProxyCache}(SOFT 참조): {@code @Bean} 메서드별
 * 캐시. 자식 {@code @Configuration}의 {@code @Bean} 메서드가 남는다.</li>
 * <li>{@code BridgeMethodResolver.cache}(SOFT 참조): 브리지 메서드 캐시. 제네릭 인터페이스를 구현한 자식
 * 클래스의 메서드가 남는다.</li>
 * <li>Spring Security {@code SecurityAnnotationScanners}의 스캐너 맵(강한 참조): 스캐너마다 메서드·파라미터별
 * 애너테이션 캐시를 가져서 {@code @PreAuthorize}, {@code @AuthenticationPrincipal} 등을 쓴 자식 메서드가 남는다.
 * 스캐너 내부 캐시는 건드리지 않고 맵만 비운다. 이전 스캐너를 들고 있던 객체는 이전 세대와 함께 사라지고, 새
 * 세대는 새 스캐너를 받는다.</li>
 * </ul>
 */
public class GenerationCacheCleaner implements Consumer<ClassLoader> {

	private static final Log logger = LogFactory.getLog(GenerationCacheCleaner.class);

	private static final String BEAN_ANNOTATION_HELPER = "org.springframework.context.annotation.BeanAnnotationHelper";

	private static final String BRIDGE_METHOD_RESOLVER = "org.springframework.core.BridgeMethodResolver";

	private static final String SECURITY_ANNOTATION_SCANNERS = "org.springframework.security.core.annotation.SecurityAnnotationScanners";

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
		clearStaticCache(BEAN_ANNOTATION_HELPER, "beanNameCache");
		clearStaticCache(BEAN_ANNOTATION_HELPER, "scopedProxyCache");
		clearStaticCache(BRIDGE_METHOD_RESOLVER, "cache");
		if (ClassUtils.isPresent(SECURITY_ANNOTATION_SCANNERS, GenerationCacheCleaner.class.getClassLoader())) {
			clearStaticCache(SECURITY_ANNOTATION_SCANNERS, "uniqueScanners");
			clearStaticCache(SECURITY_ANNOTATION_SCANNERS, "uniqueTemplateScanners");
			clearStaticCache(SECURITY_ANNOTATION_SCANNERS, "uniqueTypesScanners");
		}
		this.parentObjectMapper.ifAvailable((objectMapper) -> objectMapper.getTypeFactory().clearCache());
	}

	/**
	 * 정적 {@link Map} 필드를 비운다. Spring 버전이 달라 클래스나 필드가 없으면 건너뛴다.
	 */
	private static void clearStaticCache(String className, String fieldName) {
		try {
			Class<?> type = ClassUtils.forName(className, GenerationCacheCleaner.class.getClassLoader());
			Field field = ReflectionUtils.findField(type, fieldName);
			if (field == null) {
				logger.debug("No static cache " + className + "." + fieldName + " to clear");
				return;
			}
			ReflectionUtils.makeAccessible(field);
			if (ReflectionUtils.getField(field, null) instanceof Map<?, ?> cache) {
				cache.clear();
			}
		}
		catch (ClassNotFoundException | LinkageError | RuntimeException ex) {
			logger.debug("Failed to clear static cache " + className + "." + fieldName, ex);
		}
	}

}
