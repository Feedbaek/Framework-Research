package my.spring.research.runtime.api;

import java.util.Objects;

public record AppMetadata(String appId, String version, String runtimeApiRange) {

	public AppMetadata {
		appId = requireText(appId, "appId");
		version = requireText(version, "version");
		runtimeApiRange = requireText(runtimeApiRange, "runtimeApiRange");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return Objects.requireNonNull(value).trim();
	}
}
