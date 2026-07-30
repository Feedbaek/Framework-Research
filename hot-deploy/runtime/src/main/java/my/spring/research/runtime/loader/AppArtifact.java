package my.spring.research.runtime.loader;

import java.nio.file.Path;

public record AppArtifact(Path path, String sha256, long size) {
}
