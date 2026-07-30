package my.spring.research.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import my.spring.research.runtime.deploy.DeploymentBusyException;
import my.spring.research.runtime.deploy.DeploymentControlPlane;

final class RuntimeRefreshCoordinatorTest {
	@Test
	void appliesAllowedChangedKeysAndPublishesEnvironmentChange() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(
				new MapPropertySource("baseline", Map.of("runtime.message.prefix", "old")));
		RefreshScope refreshScope = mock(RefreshScope.class);
		List<Object> events = new ArrayList<>();
		RuntimeRefreshCoordinator coordinator = new RuntimeRefreshCoordinator(
				environment,
				refreshScope,
				events::add,
				new DeploymentControlPlane());

		RuntimeRefreshResult result = coordinator.refresh(Map.of("runtime.message.prefix", "new"));

		assertThat(result.changed()).isTrue();
		assertThat(result.changedKeys()).containsExactly("runtime.message.prefix");
		assertThat(environment.getProperty("runtime.message.prefix")).isEqualTo("new");
		assertThat(events).singleElement().isInstanceOfSatisfying(EnvironmentChangeEvent.class,
				event -> assertThat(event.getKeys()).containsExactly("runtime.message.prefix"));
		verify(refreshScope).refresh("runtimeMessageProvider");
	}

	@Test
	void returnsUnchangedWhenCandidateMatchesCurrentEnvironment() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(
				new MapPropertySource("baseline", Map.of("runtime.message.prefix", "same")));
		RefreshScope refreshScope = mock(RefreshScope.class);
		List<Object> events = new ArrayList<>();
		RuntimeRefreshCoordinator coordinator = new RuntimeRefreshCoordinator(
				environment,
				refreshScope,
				events::add,
				new DeploymentControlPlane());

		RuntimeRefreshResult result = coordinator.refresh(Map.of("runtime.message.prefix", "same"));

		assertThat(result.changed()).isFalse();
		assertThat(events).isEmpty();
		verify(refreshScope, never()).refresh("runtimeMessageProvider");
	}

	@Test
	void rejectsDisallowedKeysWithoutMutatingEnvironment() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(
				new MapPropertySource("baseline", Map.of("runtime.message.prefix", "old")));
		RefreshScope refreshScope = mock(RefreshScope.class);
		List<Object> events = new ArrayList<>();
		RuntimeRefreshCoordinator coordinator = new RuntimeRefreshCoordinator(
				environment,
				refreshScope,
				events::add,
				new DeploymentControlPlane());

		assertThatThrownBy(() -> coordinator.refresh(Map.of(
				"runtime.message.prefix", "new",
				"spring.main.allow-bean-definition-overriding", "true")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not allowed");

		assertThat(environment.getProperty("runtime.message.prefix")).isEqualTo("old");
		assertThat(environment.getPropertySources().contains(RuntimeRefreshCoordinator.PROPERTY_SOURCE_NAME)).isFalse();
		assertThat(events).isEmpty();
		verify(refreshScope, never()).refresh("runtimeMessageProvider");
	}

	@Test
	void restoresPreviousPropertySourceWhenRefreshTargetFails() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(
				new MapPropertySource("baseline", Map.of("runtime.message.prefix", "old")));
		RefreshScope refreshScope = mock(RefreshScope.class);
		doThrow(new IllegalStateException("target refresh failed"))
				.doReturn(true)
				.when(refreshScope)
				.refresh("runtimeMessageProvider");
		List<Object> events = new ArrayList<>();
		RuntimeRefreshCoordinator coordinator = new RuntimeRefreshCoordinator(
				environment,
				refreshScope,
				events::add,
				new DeploymentControlPlane());

		assertThatThrownBy(() -> coordinator.refresh(Map.of("runtime.message.prefix", "new")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("rolled back");

		assertThat(environment.getProperty("runtime.message.prefix")).isEqualTo("old");
		assertThat(environment.getPropertySources().contains(RuntimeRefreshCoordinator.PROPERTY_SOURCE_NAME)).isFalse();
		assertThat(events).hasSize(2);
		verify(refreshScope, times(2)).refresh("runtimeMessageProvider");
	}

	@Test
	void rejectsRefreshWhenDeploymentControlPlaneIsBusy() throws Exception {
		DeploymentControlPlane controlPlane = new DeploymentControlPlane();
		CountDownLatch lockHeld = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		var executor = Executors.newSingleThreadExecutor();
		executor.submit(() -> {
			controlPlane.acquire();
			lockHeld.countDown();
			try {
				releaseLock.await(1, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			finally {
				controlPlane.release();
			}
		});
		assertThat(lockHeld.await(1, TimeUnit.SECONDS)).isTrue();
		StandardEnvironment environment = new StandardEnvironment();
		RuntimeRefreshCoordinator coordinator = new RuntimeRefreshCoordinator(
				environment,
				mock(RefreshScope.class),
				event -> {
				},
				controlPlane);

		try {
			assertThatThrownBy(() -> coordinator.refresh(Map.of("runtime.message.prefix", "new")))
					.isInstanceOf(DeploymentBusyException.class)
					.hasMessageContaining("busy");
		}
		finally {
			releaseLock.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
		}
	}
}
