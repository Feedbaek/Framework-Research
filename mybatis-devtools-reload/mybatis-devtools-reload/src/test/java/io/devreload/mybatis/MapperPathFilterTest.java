package io.devreload.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapperPathFilterTest {

    @TempDir
    Path root;

    @Test
    void acceptsEverythingWithoutPatterns() {
        MapperPathFilter filter = new MapperPathFilter(List.of());

        assertThat(filter.includes(root, root.resolve("static/sitemap.xml"))).isTrue();
        assertThat(filter.mayInclude(root, root.resolve("static"))).isTrue();
    }

    @Test
    void matchesFilesRelativeToRoot() {
        MapperPathFilter filter = new MapperPathFilter(List.of("mapper/**"));

        assertThat(filter.includes(root, root.resolve("mapper/UserMapper.xml"))).isTrue();
        assertThat(filter.includes(root, root.resolve("mapper/order/OrderMapper.xml"))).isTrue();
        assertThat(filter.includes(root, root.resolve("logback.xml"))).isFalse();
        assertThat(filter.includes(root, root.resolve("static/mapper/UserMapper.xml"))).isFalse();
    }

    @Test
    void prunesDirectoriesThatCannotContainMatches() {
        MapperPathFilter filter = new MapperPathFilter(List.of("mapper/**", "sql/*.xml"));

        assertThat(filter.mayInclude(root, root)).isTrue();
        assertThat(filter.mayInclude(root, root.resolve("mapper"))).isTrue();
        assertThat(filter.mayInclude(root, root.resolve("mapper/order/detail"))).isTrue();
        assertThat(filter.mayInclude(root, root.resolve("sql"))).isTrue();
        assertThat(filter.mayInclude(root, root.resolve("sql/legacy"))).isFalse();
        assertThat(filter.mayInclude(root, root.resolve("static"))).isFalse();
        assertThat(filter.mayInclude(root, root.resolve("mappers"))).isFalse();
    }

    @Test
    void cannotPruneWhenPatternStartsWithWildcardDirectories() {
        MapperPathFilter filter = new MapperPathFilter(List.of("**/*Mapper.xml"));

        assertThat(filter.mayInclude(root, root.resolve("static/js"))).isTrue();
        assertThat(filter.includes(root, root.resolve("UserMapper.xml"))).isTrue();
        assertThat(filter.includes(root, root.resolve("a/b/UserMapper.xml"))).isTrue();
        assertThat(filter.includes(root, root.resolve("a/b/sitemap.xml"))).isFalse();
    }

    @Test
    void normalizesPatterns() {
        MapperPathFilter filter = new MapperPathFilter(List.of(" /mapper/** ", "sql\\**", " "));

        assertThat(filter.patterns()).containsExactly("mapper/**", "sql/**");
        assertThat(filter.includes(root, root.resolve("mapper/UserMapper.xml"))).isTrue();
        assertThat(filter.includes(root, root.resolve("sql/legacy/Old.xml"))).isTrue();
    }

    @Test
    void findXmlFilesSkipsExcludedTrees() throws Exception {
        Files.createDirectories(root.resolve("mapper/order"));
        Files.createDirectories(root.resolve("static"));
        Files.writeString(root.resolve("mapper/UserMapper.xml"), "<mapper namespace=\"a\"/>");
        Files.writeString(root.resolve("mapper/order/OrderMapper.xml"), "<mapper namespace=\"b\"/>");
        Files.writeString(root.resolve("mapper/notes.txt"), "ignored");
        Files.writeString(root.resolve("static/sitemap.xml"), "<urlset/>");
        Files.writeString(root.resolve("logback.xml"), "<configuration/>");

        List<Path> found = MapperFileWatcher.findXmlFiles(root, root, new MapperPathFilter(List.of("mapper/**")));

        assertThat(found).extracting(p -> root.relativize(p).toString().replace('\\', '/'))
                .containsExactlyInAnyOrder("mapper/UserMapper.xml", "mapper/order/OrderMapper.xml");
    }
}
