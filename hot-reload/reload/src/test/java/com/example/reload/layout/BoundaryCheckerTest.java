package com.example.reload.layout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class BoundaryCheckerTest {

	private static final ClassOwnership OWNERSHIP = new ClassOwnership(List.of("com.example.shared"),
			Set.of("com.example.DemoApplication"));

	@TempDir
	Path temp;

	@Test
	void reportsParentClassesReferencingChildClasses() throws IOException {
		Path classes = compile(Map.of(
				// 부모 소유 → 자식 소유: 필드 타입
				"com/example/shared/Registry.java",
				"package com.example.shared; public class Registry { com.example.web.Widget widget; }",
				// 부모 소유 → 자식 소유: 메서드 시그니처만(CONSTANT_Class 없이 서술자에만 나타남)
				"com/example/shared/Factory.java",
				"package com.example.shared; public class Factory { public com.example.web.Widget create() { return null; } }",
				// 부모 소유 → 부모 소유, JDK: 문제 없음
				"com/example/shared/Clean.java",
				"package com.example.shared; public class Clean { Registry registry; String name; }",
				// 애플리케이션 클래스(부모 소유) → 자식 소유
				"com/example/DemoApplication.java",
				"package com.example; public class DemoApplication { Object bean() { return new com.example.web.Widget(); } }",
				// 자식 소유 → 부모 소유: 허용되는 방향
				"com/example/web/Widget.java",
				"package com.example.web; public class Widget { com.example.shared.Registry registry; }"));

		Map<String, Set<String>> violations = BoundaryChecker.findViolations(List.of(classes), OWNERSHIP);

		assertThat(violations).containsOnlyKeys("com.example.shared.Registry", "com.example.shared.Factory",
				"com.example.DemoApplication");
		assertThat(violations.values()).allSatisfy((references) -> assertThat(references)
			.containsExactly("com.example.web.Widget"));
	}

	@Test
	void ownershipRules() {
		assertThat(OWNERSHIP.isParentOwned("com.example.shared.Registry")).isTrue();
		assertThat(OWNERSHIP.isParentOwned("com.example.shared.sub.Deep")).isTrue();
		assertThat(OWNERSHIP.isParentOwned("com.example.shared.Registry$Entry")).isTrue();
		assertThat(OWNERSHIP.isParentOwned("com.example.DemoApplication")).isTrue();
		assertThat(OWNERSHIP.isParentOwned("com.example.DemoApplication$$SpringCGLIB$$0")).isTrue();
		assertThat(OWNERSHIP.isParentOwned("com.example.sharedstuff.Other")).isFalse();
		assertThat(OWNERSHIP.isParentOwned("com.example.web.Widget")).isFalse();
		assertThat(OWNERSHIP.isParentOwned("com.example.DemoApplicationHelper")).isFalse();
		assertThat(ClassOwnership.classNameOf("com/example/web/Widget$1.class")).isEqualTo("com.example.web.Widget$1");
		assertThat(ClassOwnership.classNameOf("static/index.html")).isNull();
	}

	private Path compile(Map<String, String> sources) throws IOException {
		Path sourceRoot = Files.createDirectories(this.temp.resolve("src"));
		Path output = Files.createDirectories(this.temp.resolve("classes"));
		List<String> arguments = new ArrayList<>(List.of("-d", output.toString()));
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = sourceRoot.resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
			arguments.add(file.toString());
		}
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		int result = ToolProvider.getSystemJavaCompiler()
			.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new));
		assertThat(result).as(diagnostics.toString(StandardCharsets.UTF_8)).isZero();
		return output;
	}

}
