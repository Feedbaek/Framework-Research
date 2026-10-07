package io.devreload.mybatis;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.session.Configuration;

/**
 * MyBatis {@link Configuration}의 내부 registry에 리플렉션으로 접근한다.
 *
 * <p>필드는 런타임 클래스에서 시작해 {@link Configuration}까지 올라가며 찾는다. 그래서 registry를 다시 선언한
 * 하위 클래스(MyBatis-Plus의 {@code MybatisConfiguration}은 {@code mappedStatements}를 다시 선언한다)도
 * 처리된다. 가장 하위에 선언된 필드가 그 하위 클래스가 실제로 쓰는 필드이기 때문이다.
 *
 * <p>registry는 {@link java.util.HashMap}을 상속한 {@code Configuration.StrictMap} 인스턴스다.
 * {@code StrictMap}은 {@code put}/{@code get}만 오버라이드하므로, {@code remove}, entry set의
 * {@code removeIf}, {@code putAll}({@code HashMap.putVal}을 직접 호출한다)은 중복 키 검사를 거치지 않는다.
 * reloader는 이 점을 이용해 항목을 제거하고 스냅샷을 그대로 복원한다.
 */
final class ConfigurationAccessor {

    private final Configuration configuration;

    ConfigurationAccessor(Configuration configuration) {
        this.configuration = configuration;
    }

    Configuration configuration() {
        return configuration;
    }

    Map<String, Object> mappedStatements() {
        return map("mappedStatements");
    }

    Map<String, Object> resultMaps() {
        return map("resultMaps");
    }

    Map<String, Object> parameterMaps() {
        return map("parameterMaps");
    }

    Map<String, Object> keyGenerators() {
        return map("keyGenerators");
    }

    Map<String, Object> sqlFragments() {
        return map("sqlFragments");
    }

    Map<String, Object> caches() {
        return map("caches");
    }

    @SuppressWarnings("unchecked")
    Set<String> loadedResources() {
        return (Set<String>) read("loadedResources");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String name) {
        return (Map<String, Object>) read(name);
    }

    private Object read(String name) {
        Field field = findField(configuration.getClass(), name);
        try {
            return field.get(configuration);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Cannot read Configuration." + name, ex);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 상위 클래스에서 계속 찾는다
            }
        }
        throw new IllegalStateException("Field '" + name + "' not found on " + type.getName()
                + " - unsupported MyBatis version?");
    }
}
