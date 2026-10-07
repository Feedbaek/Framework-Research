package io.devreload.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class MapperReloadDevToolsEnvironmentPostProcessorTest {

    private static final String KEY = MapperReloadDevToolsEnvironmentPostProcessor.DEVTOOLS_ADDITIONAL_EXCLUDE;

    private final MapperReloadDevToolsEnvironmentPostProcessor processor =
            new MapperReloadDevToolsEnvironmentPostProcessor();

    @Test
    void doesNothingWhenDisabled() {
        StandardEnvironment env = environment(Map.of());

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(KEY)).isNull();
    }

    @Test
    void appendsDefaultPatternsToExistingExcludes() {
        StandardEnvironment env = environment(Map.of("mybatis-reload.enabled", "true", KEY, "static-extra/**"));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(KEY)).isEqualTo("static-extra/**,mapper/**,mappers/**,**/*Mapper.xml");
    }

    @Test
    void usesConfiguredPatternsWithoutDuplicates() {
        StandardEnvironment env = environment(Map.of(
                "mybatis-reload.enabled", "true",
                "mybatis-reload.devtools-restart-exclude[0]", "sql/**",
                "mybatis-reload.devtools-restart-exclude[1]", "sql/**",
                KEY, "sql/**"));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(KEY)).isEqualTo("sql/**");
    }

    @Test
    void canBeSwitchedOff() {
        StandardEnvironment env = environment(Map.of("mybatis-reload.enabled", "true",
                "mybatis-reload.devtools-integration", "false"));

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty(KEY)).isNull();
    }

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }
}
