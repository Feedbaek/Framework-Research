package com.example.reload.layout;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 부모 소유 클래스가 자식 소유 클래스를 참조하는지 검사한다.
 * <p>
 * 부모 소유 클래스는 부모 클래스로더가 로드한다. 그 클래스가 자식 소유 클래스를 참조하면 부모가 그
 * 클래스를 따로 로드하게 되어, 자식 세대의 같은 이름 클래스와 타입이 달라지고(주입·캐스팅 실패) 재로딩도
 * 되지 않는다. 같은 소스 세트 안에서는 컴파일러가 이를 막지 못하므로 시작할 때 클래스 파일의 상수 풀을
 * 읽어 확인한다(클래스 참조와 필드·메서드 시그니처의 타입).
 */
public final class BoundaryChecker {

	private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([^;<>]+)[;<]");

	private BoundaryChecker() {
	}

	/**
	 * @return 부모 소유 클래스 이름 → 참조하는 자식 소유 클래스 이름들
	 */
	public static Map<String, Set<String>> findViolations(List<Path> directories, ClassOwnership ownership) {
		Map<String, Path> classFiles = listClassFiles(directories);
		Map<String, Set<String>> violations = new LinkedHashMap<>();
		classFiles.forEach((className, file) -> {
			if (!ownership.isParentOwned(className)) {
				return;
			}
			Set<String> childReferences = new LinkedHashSet<>();
			for (String referenced : referencedClassNames(file)) {
				if (classFiles.containsKey(referenced) && !ownership.isParentOwned(referenced)) {
					childReferences.add(referenced);
				}
			}
			if (!childReferences.isEmpty()) {
				violations.put(className, childReferences);
			}
		});
		return violations;
	}

	private static Map<String, Path> listClassFiles(List<Path> directories) {
		Map<String, Path> classFiles = new LinkedHashMap<>();
		for (Path directory : directories) {
			if (!Files.isDirectory(directory)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(directory)) {
				files.filter(Files::isRegularFile).forEach((file) -> {
					String className = ClassOwnership.classNameOf(directory.relativize(file).toString());
					if (className != null) {
						classFiles.putIfAbsent(className, file);
					}
				});
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return classFiles;
	}

	/**
	 * 클래스 파일 상수 풀의 CONSTANT_Class 이름과 UTF8 서술자 안의 타입 이름을 모은다.
	 */
	static Set<String> referencedClassNames(Path classFile) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(classFile)))) {
			in.readInt(); // magic
			in.readUnsignedShort(); // minor
			in.readUnsignedShort(); // major
			int count = in.readUnsignedShort();
			String[] utf8 = new String[count];
			List<Integer> classNameIndexes = new ArrayList<>();
			for (int i = 1; i < count; i++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case 1 -> utf8[i] = in.readUTF();
					case 7 -> classNameIndexes.add(in.readUnsignedShort());
					case 8, 16, 19, 20 -> in.skipBytes(2);
					case 15 -> in.skipBytes(3);
					case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
					case 5, 6 -> {
						in.skipBytes(8);
						i++;
					}
					default -> throw new IOException("Unknown constant pool tag " + tag + " in " + classFile);
				}
			}
			Set<String> names = new LinkedHashSet<>();
			for (int index : classNameIndexes) {
				String name = utf8[index];
				if (name != null && !name.startsWith("[")) {
					names.add(name.replace('/', '.'));
				}
			}
			for (String value : utf8) {
				if (value != null && (value.indexOf('(') != -1 || value.startsWith("L") || value.startsWith("["))) {
					Matcher matcher = DESCRIPTOR_TYPE.matcher(value);
					while (matcher.find()) {
						names.add(matcher.group(1).replace('/', '.'));
					}
				}
			}
			return names;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
