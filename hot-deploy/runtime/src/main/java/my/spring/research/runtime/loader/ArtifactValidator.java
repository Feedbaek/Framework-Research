package my.spring.research.runtime.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.springframework.stereotype.Component;

import my.spring.research.runtime.config.HotDeployProperties;

@Component
public class ArtifactValidator {

	private static final String SERVICE_FILE =
		"META-INF/services/my.spring.research.runtime.api.AppModule";

	private final HotDeployProperties properties;

	public ArtifactValidator(HotDeployProperties properties) {
		this.properties = properties;
	}

	HotDeployProperties properties() {
		return properties;
	}

	public AppArtifact validate(Path candidatePath) {
		try {
			Path root = properties.getRepositoryRoot().toAbsolutePath().normalize().toRealPath();
			Path requestedPath = candidatePath.toAbsolutePath().normalize();
			if (Files.isSymbolicLink(requestedPath)) {
				throw new ArtifactValidationException("artifact must not be a symbolic link: " + requestedPath);
			}
			Path path = requestedPath.toRealPath();
			if (!path.startsWith(root)) {
				throw new ArtifactValidationException("artifact is outside repository root: " + path);
			}
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
				throw new ArtifactValidationException("artifact must be a regular JAR file: " + path);
			}
			long size = Files.size(path);
			if (size > properties.getMaxArtifactBytes()) {
				throw new ArtifactValidationException("artifact is too large in compressed form");
			}
			validateJarEntries(path);
			return new AppArtifact(path, sha256(path), size);
		}
		catch (IOException ex) {
			throw new ArtifactValidationException("failed to validate artifact: " + candidatePath, ex);
		}
	}

	private void validateJarEntries(Path path) throws IOException {
		int entries = 0;
		long uncompressedBytes = 0;
		boolean serviceFileFound = false;
		try (JarFile jarFile = new JarFile(path.toFile())) {
			var enumeration = jarFile.entries();
			while (enumeration.hasMoreElements()) {
				JarEntry entry = enumeration.nextElement();
				entries++;
				if (entries > properties.getMaxJarEntries()) {
					throw new ArtifactValidationException("artifact has too many entries");
				}
				String name = entry.getName();
				rejectPathTraversal(name);
				rejectForbiddenClass(name);
				rejectUnexpectedClass(name);
				if (SERVICE_FILE.equals(name)) {
					serviceFileFound = true;
				}
				if (!entry.isDirectory()) {
					try (InputStream input = jarFile.getInputStream(entry)) {
						byte[] buffer = new byte[8192];
						int read;
						while ((read = input.read(buffer)) != -1) {
							uncompressedBytes += read;
							if (uncompressedBytes > properties.getMaxUncompressedBytes()) {
								throw new ArtifactValidationException("artifact is too large after decompression");
							}
						}
					}
				}
			}
		}
		if (!serviceFileFound) {
			throw new ArtifactValidationException("artifact does not declare AppModule service provider");
		}
	}

	private void rejectPathTraversal(String name) {
		boolean traversal = name.startsWith("/")
				|| java.util.Arrays.stream(name.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."));
		if (traversal) {
			throw new ArtifactValidationException("artifact contains unsafe entry: " + name);
		}
	}

	private void rejectForbiddenClass(String name) {
		if (!name.endsWith(".class")) {
			return;
		}
		if (name.startsWith("org/springframework/")
			|| name.startsWith("jakarta/servlet/")
			|| name.startsWith("javax/servlet/")
			|| name.startsWith("my/spring/research/runtime/")) {
			throw new ArtifactValidationException("artifact contains forbidden class: " + name);
		}
	}

	private void rejectUnexpectedClass(String name) {
		if (!name.endsWith(".class")) {
			return;
		}
		boolean allowed = properties.getAllowedClassPrefixes().stream().anyMatch(name::startsWith);
		if (!allowed) {
			throw new ArtifactValidationException("artifact contains class outside allowed packages: " + name);
		}
	}

	private String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path);
				 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
				digestInput.transferTo(OutputStream.nullOutputStream());
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}
}
