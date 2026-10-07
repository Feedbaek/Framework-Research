package io.devreload.mybatis;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 디렉터리 트리의 {@code *.xml} 변경을 감시하고, debounce 시간 동안 파일에 변화가 없으면 변경분을 묶어서
 * 알린다(에디터는 파일 하나를 여러 단계에 걸쳐 쓰는 경우가 많다).
 *
 * <p>인스턴스마다 데몬 스레드 하나를 쓴다. {@link #close()}가 스레드를 멈춘다. reload 서비스는 자신의
 * 애플리케이션 컨텍스트가 닫힐 때 watcher를 닫는데, DevTools는 재시작할 때마다 컨텍스트를 닫으므로 스레드가
 * 자신의 클래스 로더보다 오래 살아남지 않는다.
 */
final class MapperFileWatcher implements Closeable {

    private static final Log logger = LogFactory.getLog(MapperFileWatcher.class);

    private final List<Path> roots;
    private final long debounceNanos;
    private final Consumer<Set<Path>> listener;
    private final ClassLoader contextClassLoader;
    private final Map<WatchKey, Path> keys = new HashMap<>();

    private volatile WatchService watchService;
    private volatile Thread thread;

    MapperFileWatcher(List<Path> roots, Duration debounce, ClassLoader contextClassLoader,
            Consumer<Set<Path>> listener) {
        this.roots = List.copyOf(roots);
        this.debounceNanos = debounce.toNanos();
        this.contextClassLoader = contextClassLoader;
        this.listener = listener;
    }

    synchronized void start() throws IOException {
        if (thread != null) {
            return;
        }
        watchService = roots.get(0).getFileSystem().newWatchService();
        for (Path root : roots) {
            registerTree(root);
        }
        thread = new Thread(this::run, "mybatis-mapper-watcher");
        thread.setDaemon(true);
        thread.setContextClassLoader(contextClassLoader);
        thread.start();
    }

    @Override
    public synchronized void close() {
        Thread current = thread;
        thread = null;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // 어차피 닫는 중이다
            }
        }
        if (current != null) {
            current.interrupt();
            try {
                current.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        Set<Path> pending = new LinkedHashSet<>();
        long lastEvent = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.poll(50, TimeUnit.MILLISECONDS);
                if (key != null) {
                    collect(key, pending);
                    lastEvent = System.nanoTime();
                }
                if (!pending.isEmpty() && System.nanoTime() - lastEvent >= debounceNanos) {
                    Set<Path> batch = Set.copyOf(pending);
                    pending.clear();
                    dispatch(batch);
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException ex) {
            // 중지됨
        }
    }

    private void collect(WatchKey key, Set<Path> pending) {
        Path dir = keys.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == OVERFLOW || dir == null) {
                continue;
            }
            Path changed = dir.resolve((Path) event.context());
            if (event.kind() == ENTRY_CREATE && Files.isDirectory(changed)) {
                try {
                    registerTree(changed);
                    try (Stream<Path> files = Files.walk(changed)) {
                        files.filter(MapperFileWatcher::isXmlFile).forEach(pending::add);
                    }
                } catch (IOException ex) {
                    logger.warn("Cannot watch new directory " + changed + ": " + ex.getMessage());
                }
            } else if (isXmlFile(changed)) {
                pending.add(changed);
            }
        }
        if (!key.reset()) {
            keys.remove(key);
        }
    }

    private void dispatch(Set<Path> batch) {
        try {
            listener.accept(batch);
        } catch (RuntimeException ex) {
            logger.error("Mapper change handling failed", ex);
        }
    }

    private void registerTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                keys.put(dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY), dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static boolean isXmlFile(Path path) {
        return path.getFileName() != null && path.getFileName().toString().endsWith(".xml")
                && Files.isRegularFile(path);
    }
}
