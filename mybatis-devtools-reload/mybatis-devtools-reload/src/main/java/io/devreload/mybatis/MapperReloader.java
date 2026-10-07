package io.devreload.mybatis;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;

/**
 * 동작 중인 MyBatis {@link Configuration} 안에서 mapper namespace 하나의 XML 정의 부분을 교체한다.
 *
 * <p>{@link #reload}의 절차:
 * <ol>
 * <li>namespace에서 XML로 정의된 statement, result map, parameter map, {@code <selectKey>} key generator,
 * {@code <sql>} fragment를 제거한다(새 XML이 {@code <cache>}를 선언하면 cache도 제거한다).</li>
 * <li>MyBatis의 {@link XMLMapperBuilder}로 새 XML을 파싱한다. 이때 스레드 컨텍스트 클래스 로더를 애플리케이션
 * 클래스 로더로 설정해서, {@code resultType}/{@code parameterType}이 애플리케이션이 쓰는 것과 같은 클래스
 * 로더(DevTools가 활성화돼 있으면 restart 클래스 로더)로 해석되게 한다.</li>
 * <li>파싱이 실패하거나 해석되지 않은 참조가 남으면, 등록된 것을 모두 버리고 제거했던 항목을 복원해서 이전
 * SQL이 계속 동작하게 한다.</li>
 * </ol>
 *
 * <p>mapper 인터페이스의 애노테이션({@code @Select} 등)으로 등록된 statement와 MyBatis-Plus가 주입한
 * statement는 유지된다. MyBatis가 이들의 resource를 {@code "... .java (best guess)"}로 기록하므로 XML
 * statement와 구분할 수 있다.
 *
 * <p>이미 만들어진 {@link MappedStatement}는 자신의 result map, parameter map, cache, key generator를 직접
 * 참조하므로, registry를 수정해도 애노테이션 statement는 계속 동작한다.
 *
 * <p>registry는 평범한 {@code HashMap}이다. 리로드는 {@link Configuration} 단위로 직렬화되지만, 다른
 * 스레드에서 동시에 실행 중인 쿼리가 잠깐 statement를 찾지 못할 수 있다. 개발용 도구다.
 */
public final class MapperReloader {

    private static final String ANNOTATION_RESOURCE_SUFFIX = " (best guess)";

    private final ConfigurationAccessor access;
    private final ClassLoader classLoader;

    public MapperReloader(Configuration configuration, ClassLoader classLoader) {
        this.access = new ConfigurationAccessor(configuration);
        this.classLoader = classLoader;
    }

    public Configuration configuration() {
        return access.configuration();
    }

    /**
     * 주어진 namespace에 대해 configuration이 현재 무언가를 가지고 있는지 여부.
     */
    public boolean knows(String namespace) {
        String prefix = namespace + ".";
        synchronized (access.configuration()) {
            return access.loadedResources().contains("namespace:" + namespace)
                    || hasKeyWithPrefix(access.mappedStatements(), prefix)
                    || hasKeyWithPrefix(access.resultMaps(), prefix)
                    || hasKeyWithPrefix(access.sqlFragments(), prefix)
                    || access.caches().containsKey(namespace);
        }
    }

    /**
     * mapper XML 하나를 리로드한다.
     *
     * @param resource 파일을 가리키는 안정적인 식별자. MyBatis resource 이름으로 쓰이므로 MyBatis 오류 메시지와
     *                 {@link MappedStatement#getResource()}에 나타난다
     * @param content  mapper XML
     * @return 리로드 후 namespace에 있는 XML 정의 statement의 수
     * @throws MapperReloadException XML을 적용할 수 없는 경우. 이전 상태는 복원된다
     */
    public int reload(String resource, byte[] content) {
        MapperXml xml;
        try {
            xml = MapperXml.read(content);
        } catch (Exception ex) {
            throw new MapperReloadException("Not well-formed XML: " + resource, ex);
        }
        if (xml == null) {
            throw new MapperReloadException("Not a MyBatis mapper XML: " + resource, null);
        }

        Configuration configuration = access.configuration();
        synchronized (configuration) {
            Snapshot removed = removeNamespace(xml);
            access.loadedResources().remove(resource);
            IncompleteTracker incomplete = new IncompleteTracker(configuration);

            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(classLoader);
            try {
                new XMLMapperBuilder(new ByteArrayInputStream(content), configuration, resource,
                        configuration.getSqlFragments()).parse();
                incomplete.assertNothingAdded(xml.namespace());
                return countXmlStatements(xml.namespace() + ".");
            } catch (RuntimeException ex) {
                incomplete.discardAdded();
                removeNamespace(xml);
                removed.restore();
                access.loadedResources().remove(resource);
                throw new MapperReloadException("Failed to reload " + resource + " (previous SQL kept): "
                        + rootMessage(ex), ex);
            } finally {
                thread.setContextClassLoader(previous);
                ErrorContext.instance().reset();
            }
        }
    }

    private Snapshot removeNamespace(MapperXml xml) {
        String prefix = xml.namespace() + ".";

        Map<String, Object> statements = removeByIdentity(access.mappedStatements(),
                (key, value) -> key.startsWith(prefix) && value instanceof MappedStatement ms && !isAnnotation(ms));

        // namespace의 애노테이션 statement가 아직 쓰는 result/parameter map은 등록된 채로 둔다.
        Set<Object> retained = identitySet();
        for (Object value : access.mappedStatements().values()) {
            if (value instanceof MappedStatement ms && ms.getId().startsWith(prefix)) {
                retained.addAll(ms.getResultMaps());
                if (ms.getParameterMap() != null) {
                    retained.add(ms.getParameterMap());
                }
            }
        }

        Map<String, Object> resultMaps = removeByIdentity(access.resultMaps(),
                (key, value) -> key.startsWith(prefix) && value instanceof ResultMap && !retained.contains(value));
        Map<String, Object> parameterMaps = removeByIdentity(access.parameterMaps(),
                (key, value) -> key.startsWith(prefix) && value instanceof ParameterMap && !retained.contains(value));

        // <selectKey> generator는 자기 자신의 (제거된) statement id로 등록돼 있다.
        Set<String> removedStatementIds = new java.util.HashSet<>();
        for (Object value : statements.values()) {
            removedStatementIds.add(((MappedStatement) value).getId());
        }
        Map<String, Object> keyGenerators = removeByIdentity(access.keyGenerators(),
                (key, value) -> removedStatementIds.contains(key));

        Map<String, Object> sqlFragments = removeByIdentity(access.sqlFragments(),
                (key, value) -> key.startsWith(prefix));

        Map<String, Object> caches = xml.declaresCache()
                ? removeByIdentity(access.caches(), (key, value) -> key.equals(xml.namespace()))
                : Map.of();

        return new Snapshot(statements, resultMaps, parameterMaps, keyGenerators, sqlFragments, caches);
    }

    private int countXmlStatements(String prefix) {
        Set<Object> distinct = identitySet();
        for (Map.Entry<String, Object> entry : access.mappedStatements().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof MappedStatement ms
                    && !isAnnotation(ms)) {
                distinct.add(ms);
            }
        }
        return distinct.size();
    }

    /**
     * {@code fullKeyMatch}가 고른 값을 값으로 가진 모든 항목을 제거한다. StrictMap은 각 값을 full id로 저장하고,
     * 모호하지 않으면 short id로도 저장한다. 값의 identity로 비교하므로 둘 다 제거된다.
     */
    private static Map<String, Object> removeByIdentity(Map<String, Object> map,
            BiPredicate<String, Object> fullKeyMatch) {
        Set<Object> targets = identitySet();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (fullKeyMatch.test(entry.getKey(), entry.getValue())) {
                targets.add(entry.getValue());
            }
        }
        Map<String, Object> removed = new LinkedHashMap<>();
        if (targets.isEmpty()) {
            return removed;
        }
        Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Object> entry = it.next();
            if (targets.contains(entry.getValue())) {
                removed.put(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
        return removed;
    }

    private static boolean hasKeyWithPrefix(Map<String, Object> map, String prefix) {
        for (String key : map.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnnotation(MappedStatement ms) {
        return ms.getResource() != null && ms.getResource().endsWith(ANNOTATION_RESOURCE_SUFFIX);
    }

    private static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null ? message : root.getClass().getName();
    }

    private final class Snapshot {

        private final List<Map<String, Object>> removed;

        Snapshot(Map<String, Object> statements, Map<String, Object> resultMaps, Map<String, Object> parameterMaps,
                Map<String, Object> keyGenerators, Map<String, Object> sqlFragments, Map<String, Object> caches) {
            this.removed = List.of(statements, resultMaps, parameterMaps, keyGenerators, sqlFragments, caches);
        }

        void restore() {
            // putAll은 HashMap.putVal을 직접 호출하므로 StrictMap의 중복 검사를 거치지 않는다. 원래 항목
            // (full 키와 short 키)이 있던 그대로 복원된다.
            access.mappedStatements().putAll(removed.get(0));
            access.resultMaps().putAll(removed.get(1));
            access.parameterMaps().putAll(removed.get(2));
            access.keyGenerators().putAll(removed.get(3));
            access.sqlFragments().putAll(removed.get(4));
            access.caches().putAll(removed.get(5));
        }
    }

    /**
     * MyBatis의 "incomplete" 큐를 추적한다. 파싱 후 여기에 항목이 남아 있으면 참조를 해석하지 못했다는
     * 뜻이다(예: 잘못 적은 {@code resultMap} id). 그대로 두면 MyBatis가 나중에 임의의 시점에 실패하므로, 대신
     * 리로드를 거부한다.
     */
    private static final class IncompleteTracker {

        private final List<Collection<?>> queues;
        private final List<Set<Object>> before = new ArrayList<>();

        IncompleteTracker(Configuration configuration) {
            this.queues = List.of(configuration.getIncompleteStatements(), configuration.getIncompleteResultMaps(),
                    configuration.getIncompleteCacheRefs());
            for (Collection<?> queue : queues) {
                Set<Object> existing = identitySet();
                synchronized (queue) {
                    existing.addAll(queue);
                }
                before.add(existing);
            }
        }

        void assertNothingAdded(String namespace) {
            for (int i = 0; i < queues.size(); i++) {
                Collection<?> queue = queues.get(i);
                synchronized (queue) {
                    for (Object element : queue) {
                        if (!before.get(i).contains(element)) {
                            throw new IllegalStateException("Unresolved reference in namespace '" + namespace
                                    + "' (unknown resultMap, cache-ref or sql include?)");
                        }
                    }
                }
            }
        }

        void discardAdded() {
            for (int i = 0; i < queues.size(); i++) {
                Collection<?> queue = queues.get(i);
                Set<Object> existing = before.get(i);
                synchronized (queue) {
                    queue.removeIf(element -> !existing.contains(element));
                }
            }
        }
    }
}
