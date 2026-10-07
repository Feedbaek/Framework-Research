package io.devreload.mybatis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * mapper XML만 바뀌었을 때 DevTools가 애플리케이션을 재시작하지 않게 한다.
 *
 * <p>{@code mybatis-reload.devtools-restart-exclude}를 {@code spring.devtools.restart.additional-exclude}에
 * 이어 붙이며, 사용자가 이미 설정한 값은 유지한다. config data가 로드된 뒤에 실행되고(가장 낮은 우선순위),
 * DevTools 재시작은 매번 새 {@code SpringApplication}을 실행하므로 재시작할 때마다 다시 실행된다.
 *
 * <p>속성 이름만 사용하므로 classpath에 DevTools가 없어도 된다. DevTools가 없으면 이 속성은 쓰이지 않을
 * 뿐이다.
 */
public class MapperReloadDevToolsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String DEVTOOLS_ADDITIONAL_EXCLUDE = "spring.devtools.restart.additional-exclude";

    private static final String PROPERTY_SOURCE_NAME = "mybatisReloadDevTools";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Binder binder = Binder.get(environment);
        String prefix = MapperReloadProperties.PREFIX;
        boolean enabled = binder.bind(prefix + ".enabled", Boolean.class).orElse(false);
        boolean integrate = binder.bind(prefix + ".devtools-integration", Boolean.class).orElse(true);
        if (!enabled || !integrate) {
            return;
        }

        List<String> patterns = binder.bind(prefix + ".devtools-restart-exclude", Bindable.listOf(String.class))
                .orElse(MapperReloadProperties.DEFAULT_RESTART_EXCLUDE);
        String existing = binder.bind(DEVTOOLS_ADDITIONAL_EXCLUDE, String.class).orElse("");

        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(StringUtils.commaDelimitedListToSet(existing));
        merged.addAll(patterns);
        merged.removeIf(s -> !StringUtils.hasText(s));

        List<String> trimmed = new ArrayList<>();
        merged.forEach(s -> trimmed.add(s.trim()));
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of(DEVTOOLS_ADDITIONAL_EXCLUDE, String.join(",", trimmed))));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
