package com.example.reload.layout;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.ClassUtils;

import com.example.reload.ReloadProperties;

/**
 * 부모 context가 만들어질 때 한 번 정해지는 재로딩 배치: 자식 클래스패스와 클래스 소유권.
 * <p>
 * 부모의 컴포넌트 스캔 필터({@link ReloadParentTypeExcludeFilter})와 {@code GenerationManager}(자식 스캔)가
 * 같은 인스턴스를 쓴다. 두 스캔은 {@link #isChildComponent(MetadataReader)}로 등록 대상을 나눈다.
 */
public final class ReloadLayout {

	public static final String BEAN_NAME = "com.example.reload.internalReloadLayout";

	private static final Log logger = LogFactory.getLog(ReloadLayout.class);

	private final List<Path> classpath;

	private final ClassOwnership ownership;

	private ReloadLayout(List<Path> classpath, ClassOwnership ownership) {
		this.classpath = classpath;
		this.ownership = ownership;
	}

	/**
	 * 설정과 부모 bean 정의(애플리케이션 클래스)로 배치를 정한다. {@code reload.classpath}가 비어 있으면
	 * 애플리케이션 클래스가 로드된 디렉터리를 쓴다.
	 */
	public static ReloadLayout resolve(ReloadProperties properties, ConfigurableListableBeanFactory parentBeanFactory) {
		Set<Class<?>> applicationClasses = findApplicationClasses(parentBeanFactory);
		Set<String> applicationClassNames = new LinkedHashSet<>();
		applicationClasses.forEach((type) -> applicationClassNames.add(type.getName()));
		List<Path> classpath = resolveConfiguredClasspath(properties);
		if (classpath.isEmpty()) {
			classpath = detectClasspath(applicationClasses);
			logger.info("reload.classpath not set; using application output directories " + classpath);
		}
		List<Path> engineEntries = findEngineEntries(classpath);
		if (!engineEntries.isEmpty()) {
			logger.warn("Reload classpath entries " + engineEntries + " contain the reload engine. Engine classes "
					+ "will be loaded again in every generation, so injecting or casting engine types in reloaded "
					+ "classes fails. Keep the engine jar on the application classpath only.");
		}
		return new ReloadLayout(classpath,
				new ClassOwnership(properties.getParentPackages(), applicationClassNames));
	}

	public List<Path> classpath() {
		return this.classpath;
	}

	public List<Path> watchedDirectories() {
		return this.classpath.stream().filter((path) -> !isJar(path)).toList();
	}

	public URL[] classpathUrls() {
		return this.classpath.stream().map(ReloadLayout::toUrl).toArray(URL[]::new);
	}

	public ClassOwnership ownership() {
		return this.ownership;
	}

	/**
	 * 컴포넌트 스캔에서 자식 세대가 등록할 클래스인지 본다. 부모 스캔은 이 클래스를 빼고, 자식 스캔은 이
	 * 클래스만 등록한다.
	 * <p>
	 * 자식 클래스패스 디렉터리 안에 있고, 부모 소유가 아니고, 애플리케이션 클래스({@code @SpringBootConfiguration})가
	 * 아니어야 한다. 라이브러리 jar 등 자식 클래스패스 밖의 클래스는 스캔 패키지가 겹쳐도 부모에만 등록된다.
	 */
	public boolean isChildComponent(MetadataReader metadataReader) {
		if (this.ownership.isParentOwned(metadataReader.getClassMetadata().getClassName())
				|| metadataReader.getAnnotationMetadata().isAnnotated(SpringBootConfiguration.class.getName())) {
			return false;
		}
		Path file = fileOf(metadataReader.getResource());
		return file != null && isInChildClasspath(file);
	}

	/**
	 * 파일이 자식 클래스패스 디렉터리 안에 있는지 본다.
	 */
	boolean isInChildClasspath(Path file) {
		Path normalized = file.toAbsolutePath().normalize();
		return watchedDirectories().stream().anyMatch(normalized::startsWith);
	}

	private static Path fileOf(Resource resource) {
		try {
			return resource.isFile() ? resource.getFile().toPath() : null;
		}
		catch (IOException ex) {
			return null;
		}
	}

	private static Set<Class<?>> findApplicationClasses(ConfigurableListableBeanFactory beanFactory) {
		Set<Class<?>> applicationClasses = new LinkedHashSet<>();
		for (String name : beanFactory.getBeanDefinitionNames()) {
			Class<?> type = beanFactory.getType(name, false);
			if (type != null) {
				Class<?> userClass = ClassUtils.getUserClass(type);
				if (AnnotatedElementUtils.hasAnnotation(userClass, SpringBootConfiguration.class)) {
					applicationClasses.add(userClass);
				}
			}
		}
		return applicationClasses;
	}

	private static List<Path> detectClasspath(Set<Class<?>> applicationClasses) {
		Set<Path> directories = new LinkedHashSet<>();
		for (Class<?> applicationClass : applicationClasses) {
			CodeSource codeSource = applicationClass.getProtectionDomain().getCodeSource();
			if (codeSource == null || codeSource.getLocation() == null) {
				continue;
			}
			try {
				Path location = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
				if (Files.isDirectory(location)) {
					directories.add(location);
				}
			}
			catch (URISyntaxException | IllegalArgumentException ex) {
				logger.debug("Unable to resolve code source of " + applicationClass.getName(), ex);
			}
		}
		if (directories.isEmpty()) {
			logger.warn("Could not detect an application output directory (running from a jar?). Set "
					+ "reload.classpath explicitly to enable reloading.");
		}
		return List.copyOf(directories);
	}

	/**
	 * 명시적으로 설정된 클래스패스 항목을 작업 디렉터리 기준 절대 경로로 바꾼다. 비어 있으면 빈 목록.
	 */
	private static List<Path> resolveConfiguredClasspath(ReloadProperties properties) {
		return properties.getClasspath()
			.stream()
			.map((entry) -> Path.of(entry).toAbsolutePath().normalize())
			.toList();
	}

	/**
	 * 엔진 클래스가 들어 있는 자식 클래스패스 항목을 찾는다. 엔진은 부모 클래스패스에만 있어야 한다. 자식 클래스패스에서
	 * 보이면 child-first 로딩이 엔진 타입을 세대마다 따로 로드한다.
	 */
	static List<Path> findEngineEntries(List<Path> classpath) {
		String engineClass = ReloadLayout.class.getName().replace('.', '/') + ".class";
		return classpath.stream().filter((entry) -> containsResource(entry, engineClass)).toList();
	}

	private static boolean containsResource(Path entry, String resourcePath) {
		if (Files.isDirectory(entry)) {
			return Files.isRegularFile(entry.resolve(resourcePath));
		}
		if (Files.isRegularFile(entry)) {
			try (JarFile jar = new JarFile(entry.toFile())) {
				return jar.getEntry(resourcePath) != null;
			}
			catch (IOException ex) {
				logger.debug("Unable to read reload.classpath entry " + entry, ex);
			}
		}
		return false;
	}

	private static boolean isJar(Path path) {
		return Files.isRegularFile(path) || path.getFileName().toString().endsWith(".jar");
	}

	private static URL toUrl(Path path) {
		try {
			String url = path.toUri().toURL().toString();
			// 아직 없는 디렉터리는 toUri()가 '/'를 붙이지 않는다. '/'가 없으면 URLClassLoader가 jar로 취급한다.
			if (!isJar(path) && !url.endsWith("/")) {
				url = url + "/";
			}
			return new URL(url);
		}
		catch (MalformedURLException ex) {
			throw new IllegalStateException("Invalid reload.classpath entry: " + path, ex);
		}
	}

}
