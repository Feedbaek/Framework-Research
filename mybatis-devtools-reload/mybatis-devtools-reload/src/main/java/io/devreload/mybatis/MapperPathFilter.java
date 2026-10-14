package io.devreload.mybatis;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

/**
 * 감시 루트 기준 상대 경로에 {@code mybatis-reload.watch-include}의 Ant 패턴을 적용한다.
 *
 * <p>감시 루트를 하위 디렉터리로 옮기는 것만으로는 표현하기 어려운 범위(한 루트 아래의 여러 디렉터리, 파일 이름
 * 규칙)를 패턴으로 좁힌다.
 *
 * <p>패턴이 없으면 모든 경로를 받아들인다.
 */
final class MapperPathFilter {

    private final List<String> patterns;
    private final AntPathMatcher matcher = new AntPathMatcher();

    MapperPathFilter(List<String> patterns) {
        this.patterns = patterns.stream()
                .filter(StringUtils::hasText)
                .map(MapperPathFilter::normalize)
                .toList();
    }

    List<String> patterns() {
        return patterns;
    }

    /**
     * 파일이 패턴 중 하나와 일치하는가.
     */
    boolean includes(Path root, Path file) {
        if (patterns.isEmpty()) {
            return true;
        }
        String relative = relative(root, file);
        return patterns.stream().anyMatch(pattern -> matcher.match(pattern, relative));
    }

    /**
     * 디렉터리 아래에 일치하는 파일이 있을 수 있는가. 아니면 그 하위 트리는 감시하거나 훑을 필요가 없다.
     */
    boolean mayInclude(Path root, Path dir) {
        if (patterns.isEmpty() || dir.equals(root)) {
            return true;
        }
        String relative = relative(root, dir);
        return patterns.stream().anyMatch(pattern -> matcher.matchStart(pattern, relative));
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String normalize(String pattern) {
        String normalized = pattern.trim().replace('\\', '/');
        return normalized.startsWith("/") ? normalized.substring(1) : normalized;
    }
}
