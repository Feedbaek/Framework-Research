package com.example.reload.fixture;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 부모 bean. 자식 코드가 무엇이 언제 실행됐는지 문자열로 기록한다. 자식 객체는 저장하지 않으므로 이 bean
 * 때문에 이전 세대가 붙잡히지는 않는다.
 */
public class Probe {

	private final Map<String, List<String>> events = new ConcurrentHashMap<>();

	public void record(String key, String value) {
		this.events.computeIfAbsent(key, (ignored) -> new CopyOnWriteArrayList<>()).add(value);
	}

	public List<String> events(String key) {
		return List.copyOf(this.events.getOrDefault(key, List.of()));
	}

	public int count(String key) {
		return events(key).size();
	}

	public void clear() {
		this.events.clear();
	}

	/**
	 * 자식 코드에서 자기 클래스로더를 구분하는 표시로 쓴다.
	 */
	public static String loaderId(Class<?> type) {
		return loaderId(type.getClassLoader());
	}

	public static String loaderId(ClassLoader loader) {
		return Integer.toHexString(System.identityHashCode(loader));
	}

}
