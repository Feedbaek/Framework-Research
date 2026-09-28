package com.example.reload.autoconfigure;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.example.reload.ReloadProperties;

import static org.assertj.core.api.Assertions.assertThat;

class OnReloadApiConditionTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(ApiOnlyConfiguration.class);

	@Test
	void watchModeByDefault() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean("apiMarker"));
	}

	@Test
	void matchesApiAndBoth() {
		this.runner.withPropertyValues("reload.trigger.mode=api")
			.run((context) -> assertThat(context).hasBean("apiMarker"));
		this.runner.withPropertyValues("reload.trigger.mode=both")
			.run((context) -> assertThat(context).hasBean("apiMarker"));
		this.runner.withPropertyValues("reload.trigger.mode=watch")
			.run((context) -> assertThat(context).doesNotHaveBean("apiMarker"));
	}

	@Test
	void modeWatchesAndApiFlags() {
		assertThat(ReloadProperties.Mode.WATCH.isWatch()).isTrue();
		assertThat(ReloadProperties.Mode.WATCH.isApi()).isFalse();
		assertThat(ReloadProperties.Mode.API.isWatch()).isFalse();
		assertThat(ReloadProperties.Mode.API.isApi()).isTrue();
		assertThat(ReloadProperties.Mode.BOTH.isWatch()).isTrue();
		assertThat(ReloadProperties.Mode.BOTH.isApi()).isTrue();
	}

	@Configuration(proxyBeanMethods = false)
	static class ApiOnlyConfiguration {

		@Bean
		@Conditional(OnReloadApiCondition.class)
		String apiMarker() {
			return "api";
		}

	}

}
