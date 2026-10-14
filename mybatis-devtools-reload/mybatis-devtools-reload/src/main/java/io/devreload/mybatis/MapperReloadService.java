package io.devreload.mybatis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.ClassUtils;

/**
 * mapper XML 소스 파일을 감시하고, 변경 내용을 컨텍스트의 모든 {@link SqlSessionFactory}에 적용한다.
 *
 * <p>애플리케이션 컨텍스트 안에 있으므로, DevTools를 쓰면 restart 클래스 로더가 생성하고 재시작할 때마다
 * 자신이 갱신하는 {@code SqlSessionFactory}와 함께 중지되고 다시 생성된다.
 *
 * <p>재시작한 뒤에는 MyBatis가 classpath(build output)에서 읽은 XML을 그대로 쓰고, 소스와 다시 비교하지 않는다.
 * 재시작을 일으키는 빌드가 리소스도 함께 복사한다고 전제하기 때문이다. 클래스만 컴파일하고 리소스는 복사하지 않는
 * 빌드(IntelliJ의 파일 단위 Recompile 등)는 지원 범위에서 제외했다.
 */
public class MapperReloadService implements SmartLifecycle, BeanClassLoaderAware {

    private static final Log logger = LogFactory.getLog(MapperReloadService.class);

    private final MapperReloadProperties properties;
    private final ObjectProvider<SqlSessionFactory> sqlSessionFactories;
    private final Map<Path, byte[]> appliedContent = new ConcurrentHashMap<>();

    private ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
    private volatile List<MapperReloader> reloaders = List.of();
    private volatile MapperFileWatcher watcher;
    private volatile boolean running;

    public MapperReloadService(MapperReloadProperties properties,
            ObjectProvider<SqlSessionFactory> sqlSessionFactories) {
        this.properties = properties;
        this.sqlSessionFactories = sqlSessionFactories;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void start() {
        running = true;
        List<Path> roots = resolveRoots();
        if (roots.isEmpty()) {
            logger.warn("MyBatis mapper reload disabled: none of " + properties.getWatchDirs()
                    + " exists (working directory: " + workingDirectory() + ")");
            return;
        }
        reloaders = sqlSessionFactories.orderedStream()
                .map(factory -> new MapperReloader(factory.getConfiguration(), classLoader))
                .toList();
        if (reloaders.isEmpty()) {
            logger.warn("MyBatis mapper reload disabled: no SqlSessionFactory found");
            return;
        }
        MapperPathFilter filter = new MapperPathFilter(properties.getWatchInclude());
        MapperFileWatcher newWatcher = new MapperFileWatcher(roots, filter, properties.getDebounce(), classLoader,
                this::onChange);
        try {
            newWatcher.start();
            watcher = newWatcher;
            logger.info("Watching MyBatis mapper XML in " + roots
                    + (filter.patterns().isEmpty() ? "" : " (include: " + filter.patterns() + ")"));
        } catch (IOException ex) {
            newWatcher.close();
            logger.warn("MyBatis mapper reload disabled: cannot watch " + roots + ": " + ex.getMessage());
        }
    }

    @Override
    public void stop() {
        MapperFileWatcher current = watcher;
        watcher = null;
        if (current != null) {
            current.close();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * mapper 파일 하나를 지금 리로드한다. 수동으로 호출할 때(엔드포인트, 테스트) 쓰도록 공개했다. 호출자가 파일을
     * 직접 지정하므로 {@code watch-include} 패턴은 적용하지 않는다.
     *
     * @return 파일이 하나 이상의 configuration에 적용됐으면 {@code true}
     */
    public boolean reload(Path file) {
        return apply(file.toAbsolutePath().normalize(), true);
    }

    private void onChange(Set<Path> files) {
        files.stream().sorted().forEach(file -> apply(file.toAbsolutePath().normalize(), false));
    }

    private boolean apply(Path file, boolean force) {
        byte[] content = readStable(file);
        if (content == null || content.length == 0) {
            return false;
        }
        if (!force && Arrays.equals(appliedContent.get(file), content)) {
            return false;
        }

        MapperXml xml;
        try {
            xml = MapperXml.read(content);
        } catch (Exception ex) {
            logger.warn("Skipped " + file + ": not well-formed XML (" + ex.getMessage() + ")");
            return false;
        }
        if (xml == null) {
            return false; // mapper가 아닌 다른 XML 파일
        }

        List<MapperReloader> targets = targetsFor(xml.namespace());
        if (targets.isEmpty()) {
            logger.info("Skipped " + file + ": namespace '" + xml.namespace()
                    + "' is not loaded by any SqlSessionFactory (several factories exist, cannot pick one)");
            return false;
        }

        boolean allApplied = true;
        for (MapperReloader reloader : targets) {
            try {
                int statements = reloader.reload(resourceName(file), content);
                logger.info("Reloaded mapper " + xml.namespace() + " (" + statements + " statements) from "
                        + file.getFileName());
            } catch (MapperReloadException ex) {
                allApplied = false;
                logger.error(ex.getMessage());
                if (logger.isDebugEnabled()) {
                    logger.debug("Reload failure detail", ex);
                }
            }
        }
        if (allApplied) {
            appliedContent.put(file, content);
        }
        return allApplied;
    }

    private List<MapperReloader> targetsFor(String namespace) {
        List<MapperReloader> all = reloaders;
        List<MapperReloader> knowing = all.stream().filter(r -> r.knows(namespace)).toList();
        if (knowing.isEmpty() && all.size() == 1) {
            return all; // 새 mapper 파일. 등록할 곳이 하나뿐이다
        }
        return knowing;
    }

    /**
     * 에디터는 쓰기 전에 파일을 비우기도 한다. 짧게 한 번 재시도해서 비어 있거나 쓰다 만 파일을 파싱하지 않게 한다.
     */
    private static byte[] readStable(Path file) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                byte[] content = Files.readAllBytes(file);
                if (content.length > 0) {
                    return content;
                }
            } catch (IOException ex) {
                // 한 번 재시도한다
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String resourceName(Path file) {
        return "file [" + file + "]";
    }

    private List<Path> resolveRoots() {
        Path base = workingDirectory();
        List<Path> roots = new ArrayList<>();
        for (String dir : properties.getWatchDirs()) {
            Path path = Paths.get(dir);
            Path resolved = (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
            if (Files.isDirectory(resolved)) {
                roots.add(resolved);
            }
        }
        return roots;
    }

    private static Path workingDirectory() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}
