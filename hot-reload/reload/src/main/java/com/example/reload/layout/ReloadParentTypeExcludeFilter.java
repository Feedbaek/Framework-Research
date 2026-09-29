package com.example.reload.layout;

import java.io.IOException;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * 부모 context의 컴포넌트 스캔에서 자식 소유 클래스를 뺀다.
 * <p>
 * {@code @SpringBootApplication}의 컴포넌트 스캔은 {@link TypeExcludeFilter} bean에 제외 여부를 묻는다.
 * 자식 클래스패스 디렉터리에 있는 클래스 중 부모 소유가 아닌 것은 부모에 등록되지 않고 자식 세대가
 * 스캔한다({@link ReloadLayout#isChildComponent(MetadataReader)}). 라이브러리 jar 등 자식 클래스패스 밖의
 * 클래스는 건드리지 않는다.
 */
public class ReloadParentTypeExcludeFilter extends TypeExcludeFilter {

	public static final String BEAN_NAME = "com.example.reload.internalParentTypeExcludeFilter";

	private final ReloadLayout layout;

	public ReloadParentTypeExcludeFilter(ReloadLayout layout) {
		this.layout = layout;
	}

	@Override
	public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
			throws IOException {
		return this.layout.isChildComponent(metadataReader);
	}

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof ReloadParentTypeExcludeFilter other) && this.layout == other.layout;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this.layout);
	}

}
