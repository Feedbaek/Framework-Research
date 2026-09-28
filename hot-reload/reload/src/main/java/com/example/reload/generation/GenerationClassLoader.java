package com.example.reload.generation;

import java.net.URL;

import com.example.reload.devtools.restart.classloader.RestartClassLoader;
import com.example.reload.layout.ClassOwnership;

/**
 * 세대별 클래스로더. 기본은 {@link RestartClassLoader}의 child-first지만, 부모 소유 클래스는 부모에
 * 먼저 위임한다.
 * <p>
 * 자식 클래스패스 디렉터리가 부모 클래스패스에도 있으므로(같은 {@code build/classes/java/main}), 공유
 * 타입까지 child-first로 로드하면 부모와 다른 {@code Class}가 되어 주입·캐스팅이 실패한다.
 */
class GenerationClassLoader extends RestartClassLoader {

	private final ClassOwnership ownership;

	GenerationClassLoader(ClassLoader parent, URL[] urls, ClassOwnership ownership) {
		super(parent, urls);
		this.ownership = ownership;
	}

	@Override
	public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		if (this.ownership.isParentOwned(name)) {
			return Class.forName(name, false, getParent());
		}
		return super.loadClass(name, resolve);
	}

}
