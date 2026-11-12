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

    /**
     * {@link MapperFileWatcher}를 시작한다. 감시할 디렉터리가 하나도 없거나 {@link SqlSessionFactory}가 없으면
     * 경고만 남기고 시작하지 않는다.
     *
     * <p>{@code mybatis-reload.enabled}는 여기서 확인하지 않는다. {@code true}가 아니면
     * {@link MapperReloadAutoConfiguration}의 {@code @ConditionalOnProperty} 때문에 이 bean 자체가 등록되지 않는다.
     */
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
     * 파일이 변경되었을 때 호출된다.
     *
     * @param files 변경된 파일의 경로. 여러 파일이 한 번에 변경될 수 있다.
     */
    private void onChange(Set<Path> files) {
        files.stream().sorted().forEach(
                file -> apply(file.toAbsolutePath().normalize()));
    }

    /**
     * 지정된 파일을 읽어, 이전에 적용한 내용과 다르면 {@link MapperReloader}를 통해 컨텍스트의 모든 {@link SqlSessionFactory}에 적용한다.
     * 적용에 성공하면 {@link #appliedContent}를 갱신한다.
     *
     * @param file 적용할 파일의 절대 경로.
     */
    private void apply(Path file) {
        byte[] content = readStable(file);
        if (content == null || content.length == 0) {
            return;
        }
        if (Arrays.equals(appliedContent.get(file), content)) {
            return;
        }

        MapperXmlSummary summary;
        try {
            summary = MapperXmlSummary.probe(content);
        } catch (Exception ex) {
            logger.warn("Skipped " + file + ": not well-formed XML (" + ex.getMessage() + ")");
            return;
        }
        if (summary == null) {
            return; // mapper가 아닌 다른 XML 파일
        }

        List<MapperReloader> targets = targetsFor(summary.namespace());
        if (targets.isEmpty()) {
            logger.info("Skipped " + file + ": namespace '" + summary.namespace()
                    + "' is not loaded by any SqlSessionFactory (several factories exist, cannot pick one)");
            return;
        }

        boolean allApplied = true;
        for (MapperReloader reloader : targets) {
            try {
                int statements = reloader.reload("file [" + file + "]", content);
                logger.info("Reloaded mapper " + summary.namespace() + " (" + statements + " statements) from "
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
    }

    /**
     * namespace를 알고 있는 MapperReloader를 반환한다. 여러 개가 있으면 모두 반환한다. 하나도 없으면, reloaders가
     * 하나뿐이면 그걸 반환하고, 여러 개면 리스트를 반환한다.
     */
    private List<MapperReloader> targetsFor(String namespace) {
        List<MapperReloader> all = reloaders;
        List<MapperReloader> knowing = all.stream().filter(
                r -> r.knows(namespace)).toList();
        if (knowing.isEmpty() && all.size() == 1) {
            return all; // 새 mapper 파일. 등록할 곳이 하나뿐이다
        }
        return knowing;
    }

    /**
     * 에디터는 쓰기 전에 파일을 비우기도 한다. 짧게 한 번 재시도해서 비어 있거나 쓰다 만 파일을 파싱하지 않게 한다.
     */
    private static byte[] readStable(Path file) {
        // 최대 2번 시도한다. 첫 번째 시도에서 IOException이 나거나, 읽은 내용이 비어 있으면 50ms 기다렸다가 한 번 더 시도한다.
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

    /**
     * {@code mybatis-reload.watch-dirs}를 절대 경로로 해석한다. 존재하지 않는 디렉터리는 무시한다.
     * mybatis-reload.watch-dirs가 상대 경로이면 작업 디렉터리를 기준으로 해석하고, 절대 경로이면 그대로 쓴다. (빌드 output이 아니라 소스 디렉터리를 감시한다)
     * <p>
     * 예를 들어, {@code mybatis-reload.watch-dirs}가 {@code src/main/resources/mappers}이고, 작업 디렉터리가 {@code /home/user/project}이라면,
     * {@code /home/user/project/src/main/resources/mappers}가 된다.
     * </p>
     */
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

    /**
     * 현재 작업 디렉터리. 상대 경로를 해석할 때 기준이 된다.
     */
    private static Path workingDirectory() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}
