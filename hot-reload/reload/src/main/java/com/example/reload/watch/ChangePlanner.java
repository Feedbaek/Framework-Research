package com.example.reload.watch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.reload.ReloadProperties;
import com.example.reload.layout.ReloadLayout;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.util.AntPathMatcher;

/**
 * 감시와 API가 공유하는 내용 기반 변경 분류. 클래스는 로드하지 않는다. 크기와 수정 시각이 그대로인 파일은 이전
 * 결과를 재사용하고, 바뀐 파일만 다시 읽어 해싱한다.
 */
public final class ChangePlanner {
	public enum Action { NONE, RELOAD, RESTART }

	public record Entry(String digest, String className, boolean infrastructure) { }
	public record Snapshot(Map<Path, Entry> files) {
		public Snapshot { files = Map.copyOf(files); }
	}
	public record Plan(Action action, List<String> reasons, Snapshot snapshot) {
		public Plan { reasons = List.copyOf(reasons); }
	}

	private final ReloadProperties properties;
	private final ReloadLayout layout;
	private final List<Path> roots;
	private final AntPathMatcher matcher = new AntPathMatcher();

	/**
	 * 방금 쓰인 파일은 같은 크기·수정 시각으로 다시 쓰일 수 있다(파일시스템 시각 해상도). 읽는 시점에 이만큼 지난
	 * 파일만 다음 스냅샷에서 다시 읽지 않는다.
	 */
	private static final long RECENT_WRITE_MILLIS = 3000;

	private record Known(long size, FileTime modified, Entry entry) { }

	/** 크기와 수정 시각이 그대로인 파일의 분류 결과. 문자열만 담으므로 클래스를 붙잡지 않는다. */
	private final Map<Path, Known> known = new ConcurrentHashMap<>();

	public ChangePlanner(ReloadProperties properties, ReloadLayout layout) {
		this.properties = properties;
		this.layout = layout;
		LinkedHashSet<Path> paths = new LinkedHashSet<>(layout.classpath());
		// Gradle의 classes/resources 분리와 다중 프로젝트 출력 디렉터리도 감시한다.
		for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
			if (!entry.isBlank() && (Files.isDirectory(Path.of(entry)) || entry.endsWith(".jar"))) {
				paths.add(Path.of(entry));
			}
		}
		properties.getWatchPaths().stream().map(Path::of).forEach(paths::add);
		this.roots = paths.stream().map((path) -> path.toAbsolutePath().normalize()).toList();
	}

	public Snapshot snapshot() {
		Map<Path, Entry> files = new LinkedHashMap<>();
		// 같은 디렉터리가 다른 표기(Windows 8.3 짧은 이름, 심볼릭 링크)로 두 번 들어오면 한쪽 사본이 자식 디렉터리
		// 밖으로 판정되어 업무 변경이 전체 재시작이 된다. 디렉터리는 나중에 생길 수 있으므로 매번 정규화한다.
		List<Path> childDirectories = this.layout.watchedDirectories().stream().map(ChangePlanner::canonical).toList();
		for (Path root : new LinkedHashSet<>(this.roots.stream().map(ChangePlanner::canonical).toList())) {
			if (!Files.exists(root)) {
				continue;
			}
			try {
				if (Files.isDirectory(root)) {
					for (Map.Entry<Path, BasicFileAttributes> file : regularFiles(root).entrySet()) {
						read(root, file.getKey(), file.getValue(), files, childDirectories);
					}
				}
				else {
					read(root.getParent(), root, Files.readAttributes(root, BasicFileAttributes.class), files,
							childDirectories);
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Cannot inspect reload inputs: " + root, ex);
			}
		}
		this.known.keySet().retainAll(files.keySet());
		return new Snapshot(files);
	}

	/**
	 * 디렉터리 아래의 일반 파일과 속성. 스냅샷은 순서를 따지지 않으므로 정렬하지 않는다. 속성은 디렉터리 순회에서 함께
	 * 얻어 파일마다 다시 묻지 않는다.
	 */
	private static Map<Path, BasicFileAttributes> regularFiles(Path root) throws IOException {
		Map<Path, BasicFileAttributes> found = new LinkedHashMap<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				if (attributes.isSymbolicLink()) {
					if (!Files.isRegularFile(file)) {
						return FileVisitResult.CONTINUE;
					}
					attributes = Files.readAttributes(file, BasicFileAttributes.class);
				}
				if (attributes.isRegularFile()) {
					found.put(file, attributes);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return found;
	}

	private static Path canonical(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		try {
			return absolute.toRealPath();
		}
		catch (IOException ex) {
			return absolute;
		}
	}

	private void read(Path root, Path file, BasicFileAttributes attributes, Map<Path, Entry> files,
			List<Path> childDirectories) throws IOException {
		String relative = root.relativize(file).toString().replace('\\', '/');
		if (this.properties.getExcludePatterns().stream().anyMatch((pattern) -> this.matcher.match(pattern, relative))) {
			return;
		}
		if (relative.endsWith(".jar")) {
			// 의존성 전체를 매 폴링마다 해싱하지 않는다. JAR 변경은 크기/수정 시각으로 보수적으로 재시작한다.
			files.put(file, new Entry(attributes.size() + ":" + attributes.lastModifiedTime(), null, true));
			return;
		}
		Known known = this.known.get(file);
		if (known != null && known.size() == attributes.size()
				&& known.modified().equals(attributes.lastModifiedTime())) {
			files.put(file, known.entry());
			return;
		}
		long readAt = System.currentTimeMillis();
		byte[] bytes = Files.readAllBytes(file);
		String className = null;
		boolean infrastructure = true;
		if (relative.endsWith(".class")) {
			try {
				InfrastructureVisitor visitor = new InfrastructureVisitor();
				ClassReader reader = new ClassReader(bytes);
				className = reader.getClassName().replace('/', '.');
				reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				infrastructure = visitor.infrastructure;
				if (childDirectories.stream().noneMatch(file::startsWith)) {
					infrastructure = true;
				}
			}
			catch (RuntimeException ex) {
				// 불완전한/지원하지 않는 class 파일을 업무 변경으로 오인하지 않는다.
				infrastructure = true;
			}
		}
		Entry entry = new Entry(digest(bytes), className, infrastructure);
		files.put(file, entry);
		if (bytes.length == attributes.size()
				&& attributes.lastModifiedTime().toMillis() < readAt - RECENT_WRITE_MILLIS) {
			this.known.put(file, new Known(attributes.size(), attributes.lastModifiedTime(), entry));
		}
		else {
			this.known.remove(file);
		}
	}

	public Plan plan(Snapshot baseline, Snapshot candidate) {
		Action action = Action.NONE;
		List<String> reasons = new ArrayList<>();
		LinkedHashSet<Path> paths = new LinkedHashSet<>(baseline.files().keySet());
		paths.addAll(candidate.files().keySet());
		for (Path path : paths) {
			Entry before = baseline.files().get(path);
			Entry after = candidate.files().get(path);
			if (java.util.Objects.equals(before, after)) {
				continue;
			}
			boolean restart = !this.properties.isHybrid() || requiresRestart(before) || requiresRestart(after);
			if (restart) {
				action = Action.RESTART;
			}
			else if (action == Action.NONE) {
				action = Action.RELOAD;
			}
			reasons.add((restart ? "infrastructure: " : "business: ") + path);
		}
		return new Plan(action, reasons, candidate);
	}

	private boolean requiresRestart(Entry entry) {
		return entry != null && (entry.className() == null || entry.infrastructure()
				|| this.layout.ownership().isParentOwned(entry.className()));
	}

	private static String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static final class InfrastructureVisitor extends ClassVisitor {
		private boolean infrastructure;

		InfrastructureVisitor() { super(Opcodes.ASM9); }

		private void annotation(String descriptor) {
			if (descriptor.contains("/Enable") || descriptor.endsWith("/Configuration;")
					|| descriptor.contains("/ConfigurationProperties;") || descriptor.endsWith("/Bean;")
					|| descriptor.startsWith("Ljakarta/persistence/") || descriptor.startsWith("Ljavax/persistence/")
					|| descriptor.startsWith("Lorg/springframework/data/")
					|| descriptor.startsWith("Lorg/apache/ibatis/annotations/")
					|| descriptor.equals("Lorg/springframework/stereotype/Repository;")
					|| descriptor.contains("/Scheduled;") || descriptor.contains("/Schedules;")
					|| descriptor.contains("/KafkaListener") || descriptor.contains("/RabbitListener")
					|| descriptor.contains("/JmsListener")) {
				this.infrastructure = true;
			}
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			for (String type : interfaces) {
				if (type.endsWith("BeanPostProcessor") || type.endsWith("BeanFactoryPostProcessor")
						|| type.endsWith("ServletContextInitializer") || type.endsWith("WebMvcConfigurer")
						|| type.equals("jakarta/servlet/Filter") || type.equals("jakarta/servlet/Servlet")
						|| type.equals("org/springframework/aop/Advisor")) {
					this.infrastructure = true;
				}
			}
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			annotation(descriptor);
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					annotation(descriptor);
					return null;
				}
			};
		}
	}
}
