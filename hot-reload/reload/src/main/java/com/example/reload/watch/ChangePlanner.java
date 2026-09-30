package com.example.reload.watch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.example.reload.ReloadProperties;
import com.example.reload.layout.ReloadLayout;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.util.AntPathMatcher;

/** 감시와 API가 공유하는 내용 기반 변경 분류. 클래스는 로드하지 않는다. */
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
		for (Path root : this.roots) {
			if (!Files.exists(root)) {
				continue;
			}
			try {
				if (Files.isDirectory(root)) {
					try (var stream = Files.walk(root)) {
						for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
							read(root, file, files);
						}
					}
				}
				else {
					read(root.getParent(), root, files);
				}
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Cannot inspect reload inputs: " + root, ex);
			}
		}
		return new Snapshot(files);
	}

	private void read(Path root, Path file, Map<Path, Entry> files) throws IOException {
		String relative = root.relativize(file).toString().replace('\\', '/');
		if (this.properties.getExcludePatterns().stream().anyMatch((pattern) -> this.matcher.match(pattern, relative))) {
			return;
		}
		if (relative.endsWith(".jar")) {
			// 의존성 전체를 매 폴링마다 해싱하지 않는다. JAR 변경은 크기/수정 시각으로 보수적으로 재시작한다.
			files.put(file, new Entry(Files.size(file) + ":" + Files.getLastModifiedTime(file), null, true));
			return;
		}
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
				if (this.layout.watchedDirectories().stream()
						.noneMatch((directory) -> file.startsWith(directory.toAbsolutePath().normalize()))) {
					infrastructure = true;
				}
			}
			catch (RuntimeException ex) {
				// 불완전한/지원하지 않는 class 파일을 업무 변경으로 오인하지 않는다.
				infrastructure = true;
			}
		}
		files.put(file, new Entry(digest(bytes), className, infrastructure));
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
