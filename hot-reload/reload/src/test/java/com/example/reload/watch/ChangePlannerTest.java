package com.example.reload.watch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import com.example.reload.ReloadProperties;
import com.example.reload.layout.ReloadLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ChangePlannerTest {
	@TempDir Path directory;

	private ChangePlanner planner(boolean hybrid) {
		ReloadProperties properties = new ReloadProperties();
		properties.setClasspath(List.of(this.directory.toString()));
		if (hybrid) { properties.setBusinessPackages(List.of("test.business")); }
		return new ChangePlanner(properties, ReloadLayout.resolve(properties, new DefaultListableBeanFactory()));
	}

	@Test
	void businessChangesReloadButMixedParentChangesRestart() throws Exception {
		ChangePlanner planner = planner(true);
		writeClass("test/business/Service", 1, null);
		writeClass("test/infra/Pool", 1, null);
		var before = planner.snapshot();
		writeClass("test/business/Service", 2, null);
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RELOAD);
		writeClass("test/infra/Pool", 2, null);
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RESTART);
	}

	@Test
	void resourceConfigurationUnknownAndDeletedInfrastructureRequireRestart() throws Exception {
		ChangePlanner planner = planner(true);
		var empty = planner.snapshot();
		Path configuration = writeClass("test/business/Config", 1,
				"Lorg/springframework/context/annotation/Configuration;");
		var configured = planner.snapshot();
		assertThat(planner.plan(empty, configured).action()).isEqualTo(ChangePlanner.Action.RESTART);
		Files.delete(configuration);
		assertThat(planner.plan(configured, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RESTART);
		Files.writeString(this.directory.resolve("application.yml"), "server.port: 8888");
		assertThat(planner.plan(empty, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RESTART);
	}

	@Test
	void deletedBusinessClassReloadsAndIdenticalBytesDoNotTrigger() throws Exception {
		ChangePlanner planner = planner(true);
		Path service = writeClass("test/business/Service", 1, null);
		var before = planner.snapshot();
		writeClass("test/business/Service", 1, null);
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.NONE);
		Files.delete(service);
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RELOAD);
	}

	@Test
	void unchangedOldFilesAreReusedButLaterEditsOfTheSameSizeAreStillDetected() throws Exception {
		ChangePlanner planner = planner(true);
		Path service = writeClass("test/business/Service", 1, null);
		// 방금 쓰인 파일은 재사용하지 않으므로 오래된 파일로 만든다.
		Files.setLastModifiedTime(service, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
		long size = Files.size(service);
		var before = planner.snapshot();
		assertThat(planner.snapshot()).isEqualTo(before);
		writeClass("test/business/Service", 2, null);
		assertThat(Files.size(service)).isEqualTo(size);
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RELOAD);
	}

	@Test
	void noBusinessOptInUsesFullRestartEvenForPlainClasses() throws Exception {
		ChangePlanner planner = planner(false);
		var before = planner.snapshot();
		writeClass("test/business/Service", 1, null);
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RESTART);
	}

	@Test
	void malformedBytecodeIsNeverTreatedAsBusinessChange() throws Exception {
		ChangePlanner planner = planner(true);
		Path service = writeClass("test/business/Service", 1, null);
		var before = planner.snapshot();
		Files.writeString(service, "partial compiler output");
		assertThat(planner.plan(before, planner.snapshot()).action()).isEqualTo(ChangePlanner.Action.RESTART);
	}

	private Path writeClass(String name, int version, String annotation) throws Exception {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "VERSION", "I", null, version).visitEnd();
		if (annotation != null) { writer.visitAnnotation(annotation, true).visitEnd(); }
		writer.visitEnd();
		Path file = this.directory.resolve(name + ".class");
		Files.createDirectories(file.getParent());
		Files.write(file, writer.toByteArray());
		return file;
	}
}
