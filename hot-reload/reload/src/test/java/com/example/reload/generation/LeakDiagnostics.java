package com.example.reload.generation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.aspectj.ShadowMatchUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.ReflectionUtils;

import com.example.reload.devtools.restart.classloader.RestartClassLoader;

/**
 * 누수 테스트 실패 시, 부모 쪽에서 자식 클래스로더의 클래스를 붙잡고 있는 캐시를 찾아 보여 준다.
 * <p>
 * 부모 context의 모든 singleton에서 시작해 필드를 따라가며(Spring·테스트 클래스만, 깊이 제한) {@link Map}을
 * 찾고, 키나 값이 자식 클래스(또는 그 {@link Method}·인스턴스)를 가리키는 항목 수를 센다. 알려진 static
 * 캐시도 함께 본다.
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
		return findings.isEmpty() ? "\n  (no parent-side map references child classes)"
				: "\n  " + String.join("\n  ", findings);
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
