package com.example.reload.generation;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import com.example.app.library.LibraryAutoConfiguration;
import com.example.app.library.LibraryClient;
import com.example.app.library.LibraryComponent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자식 스캔 패키지({@code com.example.app})와 겹치는 라이브러리 클래스(자식 클래스패스 밖)가 어느 context에
 * 등록되는지 확인한다.
 */
class LibraryScanScenarioTest {

	@Test
	void libraryClassesInBasePackageAreRegisteredOnlyInParent() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(Map.of(), List.of("--test.library.enabled=true"))) {
			ConfigurableListableBeanFactory parent = app.context().getBeanFactory();
			ConfigurableListableBeanFactory child = app.manager().current().context().getBeanFactory();
			report("parent", parent);
			report("child", child);

			assertThat(parent.getBeanNamesForType(LibraryComponent.class)).hasSize(1);
			assertThat(parent.getBeanNamesForType(LibraryAutoConfiguration.class)).hasSize(1);
			assertThat(parent.getBeanNamesForType(LibraryClient.class)).hasSize(1);
			assertThat(child.getBeanDefinitionNames())
				.as("child must not contain Spring Boot auto-configurations")
				.noneMatch((name) -> name.startsWith("org.springframework.boot.autoconfigure"));
			assertThat(child.getBeanNamesForType(LibraryComponent.class)).as("child LibraryComponent").isEmpty();
			assertThat(child.getBeanNamesForType(LibraryAutoConfiguration.class)).as("child LibraryAutoConfiguration")
				.isEmpty();
			assertThat(child.getBeanNamesForType(LibraryClient.class)).as("child LibraryClient").isEmpty();
		}
	}

	private static void report(String label, ConfigurableListableBeanFactory beanFactory) {
		System.out.println("[" + label + "] library beans: " + Arrays.stream(beanFactory.getBeanDefinitionNames())
			.filter((name) -> name.toLowerCase().contains("library"))
			.toList());
	}

}
