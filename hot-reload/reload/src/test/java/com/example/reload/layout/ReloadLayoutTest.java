package com.example.reload.layout;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadLayoutTest {

	private static final String ENGINE_CLASS = ReloadLayout.class.getName().replace('.', '/') + ".class";

	@TempDir
	Path temp;

	@Test
	void findsEngineInChildClasspath() throws IOException, URISyntaxException {
		Path engineLocation = Path.of(ReloadLayout.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path appDirectory = Files.createDirectories(this.temp.resolve("app"));
		Path mergedDirectory = this.temp.resolve("merged");
		Files.createDirectories(mergedDirectory.resolve(ENGINE_CLASS).getParent());
		Files.createFile(mergedDirectory.resolve(ENGINE_CLASS));
		Path engineJar = this.temp.resolve("reload.jar");
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(engineJar))) {
			jar.putNextEntry(new JarEntry(ENGINE_CLASS));
			jar.closeEntry();
		}
		Path libraryJar = this.temp.resolve("library.jar");
		new JarOutputStream(Files.newOutputStream(libraryJar)).close();
		Path missing = this.temp.resolve("missing");

		List<Path> engineEntries = ReloadLayout.findEngineEntries(
				List.of(appDirectory, engineLocation, mergedDirectory, engineJar, libraryJar, missing));

		assertThat(engineEntries).containsExactly(engineLocation, mergedDirectory, engineJar);
	}

}
