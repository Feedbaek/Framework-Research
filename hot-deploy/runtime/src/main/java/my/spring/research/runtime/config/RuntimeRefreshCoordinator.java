package my.spring.research.runtime.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import my.spring.research.runtime.deploy.DeploymentBusyException;
import my.spring.research.runtime.deploy.DeploymentControlPlane;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScope;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Service;

@Service
public class RuntimeRefreshCoordinator {
	static final String PROPERTY_SOURCE_NAME = "runtimeRefreshOverrides";
	private static final Set<String> SUPPORTED_KEYS = Set.of(
			"runtime.message.prefix",
			"runtime.hot-deploy.drain-timeout",
			"runtime.hot-deploy.rollback-retention-count",
			"runtime.hot-deploy.rollback-retention-ttl");

	private final ConfigurableEnvironment environment;
	private final RefreshScope refreshScope;
	private final ApplicationEventPublisher eventPublisher;
	private final DeploymentControlPlane controlPlane;
	private final HotDeployProperties properties;

	@Autowired
	public RuntimeRefreshCoordinator(
			ConfigurableEnvironment environment,
			RefreshScope refreshScope,
			ApplicationEventPublisher eventPublisher,
			DeploymentControlPlane controlPlane,
			HotDeployProperties properties) {
		this.environment = environment;
		this.refreshScope = refreshScope;
		this.eventPublisher = eventPublisher;
		this.controlPlane = controlPlane;
		this.properties = properties;
	}

	RuntimeRefreshCoordinator(
			ConfigurableEnvironment environment,
			RefreshScope refreshScope,
			ApplicationEventPublisher eventPublisher,
			DeploymentControlPlane controlPlane) {
		this(environment, refreshScope, eventPublisher, controlPlane, new HotDeployProperties());
	}

	public RuntimeRefreshResult refresh(Map<String, String> candidate) {
		if (!controlPlane.tryAcquire()) {
			throw new DeploymentBusyException("Deployment control plane is busy");
		}
		try {
			Map<String, Object> validated = validate(candidate == null ? Map.of() : candidate);
			Set<String> changedKeys = changedKeys(validated);
			if (changedKeys.isEmpty()) {
				return new RuntimeRefreshResult(false, Set.of(), "No refreshable runtime configuration changed");
			}
			PropertySource<?> previous = environment.getPropertySources().get(PROPERTY_SOURCE_NAME);
			replaceOverrideSource(validated);
			try {
				applyRefresh(changedKeys);
			}
			catch (RuntimeException refreshFailure) {
				restoreOverrideSource(previous);
				try {
					applyRefresh(changedKeys);
				}
				catch (RuntimeException rollbackFailure) {
					refreshFailure.addSuppressed(rollbackFailure);
				}
				throw new IllegalStateException("Runtime refresh failed and configuration was rolled back", refreshFailure);
			}
			return new RuntimeRefreshResult(true, changedKeys, "Runtime refresh completed");
		}
		finally {
			controlPlane.release();
		}
	}

	private Map<String, Object> validate(Map<String, String> candidate) {
		Map<String, Object> validated = new HashMap<>();
		Set<String> allowedKeys = Set.copyOf(properties.getRefreshAllowlist());
		for (var entry : candidate.entrySet()) {
			String key = entry.getKey();
			String value = entry.getValue();
			if (!allowedKeys.contains(key) || !SUPPORTED_KEYS.contains(key)) {
				throw new IllegalArgumentException("Refresh key is not allowed: " + key);
			}
			validated.put(key, validateValue(key, value));
		}
		return validated;
	}

	private Object validateValue(String key, String value) {
		if (value == null) {
			throw new IllegalArgumentException("Refresh value must not be null: " + key);
		}
		return switch (key) {
			case "runtime.message.prefix" -> value;
			case "runtime.hot-deploy.drain-timeout", "runtime.hot-deploy.rollback-retention-ttl" -> {
				Duration duration = parseDuration(value);
				if (duration.isNegative() || duration.isZero()) {
					throw new IllegalArgumentException("Duration must be positive: " + key);
				}
				yield value;
			}
			case "runtime.hot-deploy.rollback-retention-count" -> {
				int count = Integer.parseInt(value);
				if (count < 0) {
					throw new IllegalArgumentException("Retention count must be non-negative");
				}
				yield value;
			}
			default -> throw new IllegalArgumentException("Refresh key is not allowed: " + key);
		};
	}

	private Duration parseDuration(String value) {
		String normalized = value.trim().toLowerCase();
		if (normalized.matches("\\d+ms")) {
			return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
		}
		if (normalized.matches("\\d+[smh]")) {
			long amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
			return switch (normalized.charAt(normalized.length() - 1)) {
				case 's' -> Duration.ofSeconds(amount);
				case 'm' -> Duration.ofMinutes(amount);
				case 'h' -> Duration.ofHours(amount);
				default -> throw new IllegalArgumentException("Unsupported duration: " + value);
			};
		}
		return Duration.parse(value);
	}

	private Set<String> changedKeys(Map<String, Object> validated) {
		Set<String> changed = new LinkedHashSet<>();
		for (var entry : validated.entrySet()) {
			String current = environment.getProperty(entry.getKey());
			if (!String.valueOf(entry.getValue()).equals(current)) {
				changed.add(entry.getKey());
			}
		}
		return changed;
	}

	private void replaceOverrideSource(Map<String, Object> validated) {
		var sources = environment.getPropertySources();
		var existing = sources.get(PROPERTY_SOURCE_NAME);
		Map<String, Object> merged = existing instanceof MapPropertySource mapPropertySource
				? new HashMap<>(mapPropertySource.getSource())
				: new HashMap<>();
		merged.putAll(validated);
		var replacement = new MapPropertySource(PROPERTY_SOURCE_NAME, Map.copyOf(merged));
		if (existing == null) {
			sources.addFirst(replacement);
		}
		else {
			sources.replace(PROPERTY_SOURCE_NAME, replacement);
		}
	}

	private void restoreOverrideSource(PropertySource<?> previous) {
		var sources = environment.getPropertySources();
		if (previous == null) {
			sources.remove(PROPERTY_SOURCE_NAME);
		}
		else if (sources.contains(PROPERTY_SOURCE_NAME)) {
			sources.replace(PROPERTY_SOURCE_NAME, previous);
		}
		else {
			sources.addFirst(previous);
		}
	}

	private void applyRefresh(Set<String> changedKeys) {
		eventPublisher.publishEvent(new EnvironmentChangeEvent(changedKeys));
		refreshScope.refresh("runtimeMessageProvider");
	}
}
