package com.example.reload.generation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.aspectj.ShadowMatchUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.util.ReflectionUtils;

import com.example.reload.devtools.restart.classloader.RestartClassLoader;

/**
 * 누수 테스트 실패 시, 부모 쪽에서 자식 클래스로더의 클래스를 붙잡고 있는 캐시를 찾아 보여 준다.
 * <p>
 * 부모 context의 모든 singleton에서 시작해 필드를 따라가며(Spring·테스트 클래스만, 깊이 제한) {@link Map}을
 * 찾고, 키나 값이 자식 클래스(또는 그 {@link Method}·인스턴스)를 가리키는 항목 수를 센다. 알려진 static
 * 캐시와 context class loader가 자식 클래스로더인 스레드도 함께 본다.
 */
final class LeakDiagnostics {

	private static final int MAX_DEPTH = 4;

	private LeakDiagnostics() {
	}

	static String describe(ConfigurableApplicationContext parentContext) {
		List<String> findings = new ArrayList<>();
		Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ConfigurableListableBeanFactory beanFactory = parentContext.getBeanFactory();
		for (String name : beanFactory.getSingletonNames()) {
			Object bean = beanFactory.getSingleton(name);
			if (bean != null) {
				scan(bean, "parent bean '" + name + "'", 0, visited, findings);
			}
		}
		Map<?, ?> shadowMatches = (Map<?, ?>) read(ReflectionUtils.findField(ShadowMatchUtils.class, "shadowMatchCache"),
				null);
		scanMap(shadowMatches, "static ShadowMatchUtils.shadowMatchCache", findings);
		// @Bean 메서드별 bean 이름·scoped proxy 여부 캐시(SOFT 참조). 자식 @Configuration의 @Bean 메서드가 남는다.
		for (String name : List.of("beanNameCache", "scopedProxyCache")) {
			scanStaticMap("org.springframework.context.annotation.BeanAnnotationHelper", name, findings);
		}
		// 제네릭 인터페이스 구현의 브리지 메서드 캐시(SOFT 참조).
		scanStaticMap("org.springframework.core.BridgeMethodResolver", "cache", findings);
		// Spring Security 보안 애너테이션 스캐너(스캐너마다 메서드·파라미터 캐시를 가진다).
		for (String name : List.of("uniqueScanners", "uniqueTemplateScanners", "uniqueTypesScanners")) {
			scanStaticMap("org.springframework.security.core.annotation.SecurityAnnotationScanners", name, findings);
		}
		scanThreads(findings);
		return findings.isEmpty() ? "\n  (no parent-side map references child classes)"
				: "\n  " + String.join("\n  ", findings);
	}

	private static void scanStaticMap(String className, String fieldName, List<String> findings) {
		try {
			Field field = ReflectionUtils.findField(Class.forName(className), fieldName);
			if (read(field, null) instanceof Map<?, ?> map) {
				scanMap(map, "static " + className.substring(className.lastIndexOf('.') + 1) + "." + fieldName,
						findings);
			}
		}
		catch (ClassNotFoundException ex) {
			// 해당 Spring 버전에 없는 캐시
		}
	}

	/**
	 * 살아 있는 스레드 중 context class loader가 자식 클래스로더인 것. 풀 스레드는 만든 스레드의 TCCL을
	 * 물려받으므로, 요청 처리 중(TCCL = 세대 클래스로더)에 만들어진 부모 풀 스레드가 세대를 붙잡는다.
	 */
	private static void scanThreads(List<String> findings) {
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.getContextClassLoader() instanceof RestartClassLoader) {
				findings.add("thread '" + thread.getName() + "': context class loader is a generation class loader");
			}
		}
	}

	private static void scan(Object object, String path, int depth, Set<Object> visited, List<String> findings) {
		if (object == null || depth > MAX_DEPTH || !visited.add(object)) {
			return;
		}
		if (object instanceof Map<?, ?> map) {
			scanMap(map, path, findings);
			return;
		}
		if (!isInspectable(object.getClass())) {
			return;
		}
		for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
					continue;
				}
				Object value = read(field, object);
				if (value instanceof Map<?, ?> || (value != null && isInspectable(value.getClass()))) {
					scan(value, path + "." + field.getName(), depth + 1, visited, findings);
				}
			}
		}
	}

	private static void scanMap(Map<?, ?> map, String path, List<String> findings) {
		long childEntries;
		try {
			childEntries = map.entrySet()
				.stream()
				.filter((entry) -> referencesChild(entry.getKey(), 0) || referencesChild(entry.getValue(), 0))
				.count();
		}
		catch (RuntimeException ex) {
			return;
		}
		if (childEntries > 0) {
			findings.add(path + ": " + childEntries + " of " + map.size() + " entries reference child classes");
		}
	}

	/**
	 * 값이 자식 클래스로더가 로드한 클래스, 그 클래스의 메서드, 그 클래스의 인스턴스인지(또는 필드로 가리키는지) 본다.
	 */
	private static boolean referencesChild(Object value, int depth) {
		if (value == null || depth > 2) {
			return false;
		}
		if (value instanceof ResolvableType type) {
			// 이벤트 multicaster의 리스너 캐시 키처럼 제네릭 인자에 자식 클래스가 있는 경우(PayloadApplicationEvent<Child>)
			return referencesChild(type.resolve(), depth + 1) || Arrays.stream(type.getGenerics())
				.anyMatch((generic) -> referencesChild(generic.resolve(), depth + 1));
		}
		Class<?> referenced = (value instanceof Method method) ? method.getDeclaringClass()
				: (value instanceof Class<?> clazz) ? clazz : value.getClass();
		if (referenced.getClassLoader() instanceof RestartClassLoader) {
			return true;
		}
		if (value instanceof Method || value instanceof Class<?> || !isInspectable(value.getClass())) {
			return false;
		}
		for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()
						&& referencesChild(read(field, value), depth + 1)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isInspectable(Class<?> type) {
		String name = type.getName();
		return name.startsWith("org.springframework.") || name.startsWith("com.example.");
	}

	private static Object read(Field field, Object target) {
		try {
			ReflectionUtils.makeAccessible(field);
			return ReflectionUtils.getField(field, target);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

}
