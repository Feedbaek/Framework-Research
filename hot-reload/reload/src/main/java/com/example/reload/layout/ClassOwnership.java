package com.example.reload.layout;

import java.util.List;
import java.util.Set;

/**
 * 애플리케이션 클래스가 부모 소유인지 자식(재로딩 대상) 소유인지 판단한다.
 * <p>
 * 부모 소유: 엔진 패키지({@value #ENGINE_PACKAGE}), {@code reload.parent-packages}, 애플리케이션
 * 클래스({@code @SpringBootConfiguration})와 그 중첩 클래스. 나머지는 자식 소유다.
 */
public final class ClassOwnership {

	static final String ENGINE_PACKAGE = "com.example.reload";

	private final List<String> parentPackages;

	private final Set<String> applicationClassNames;

	ClassOwnership(List<String> parentPackages, Set<String> applicationClassNames) {
		this.parentPackages = parentPackages.stream().map(String::trim).filter((name) -> !name.isEmpty()).toList();
		this.applicationClassNames = Set.copyOf(applicationClassNames);
	}

	public boolean isParentOwned(String className) {
		String topLevelName = topLevelName(className);
		return isInPackage(topLevelName, ENGINE_PACKAGE)
				|| this.parentPackages.stream().anyMatch((parentPackage) -> isInPackage(topLevelName, parentPackage))
				|| this.applicationClassNames.contains(topLevelName);
	}

	public List<String> parentPackages() {
		return this.parentPackages;
	}

	Set<String> applicationClassNames() {
		return this.applicationClassNames;
	}

	/**
	 * {@code com/example/Foo$Bar.class} 형태의 리소스 경로를 클래스 이름으로 바꾼다. 클래스 파일이 아니면
	 * {@code null}.
	 */
	public static String classNameOf(String resourcePath) {
		if (!resourcePath.endsWith(".class")) {
			return null;
		}
		String path = resourcePath.replace('\\', '/');
		return path.substring(0, path.length() - ".class".length()).replace('/', '.');
	}

	private static String topLevelName(String className) {
		int nested = className.indexOf('$');
		return (nested != -1) ? className.substring(0, nested) : className;
	}

	private static boolean isInPackage(String className, String packageName) {
		return className.startsWith(packageName + ".");
	}

}
