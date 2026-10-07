package io.devreload.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapperFileWatcherTest {

    @TempDir
    Path root;

    @Test
    void reportsXmlChangesInNestedAndNewDirectories() throws Exception {
        Path existing = Files.createDirectories(root.resolve("mapper"));
        Set<Path> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(1);

        try (MapperFileWatcher watcher = new MapperFileWatcher(List.of(root), Duration.ofMillis(100),
                getClass().getClassLoader(), batch -> {
                    seen.addAll(batch);
                    if (seen.size() >= 2) {
                        latch.countDown();
                    }
                })) {
            watcher.start();

            Files.writeString(existing.resolve("UserMapper.xml"), "<mapper namespace=\"a\"/>");
            Files.writeString(existing.resolve("notes.txt"), "ignored");
            Path created = Files.createDirectories(root.resolve("mapper/order"));
            Thread.sleep(200); // watcher가 새 디렉터리를 등록할 시간을 준다
            Files.writeString(created.resolve("OrderMapper.xml"), "<mapper namespace=\"b\"/>");

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seen).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("UserMapper.xml", "OrderMapper.xml");
    }
}
