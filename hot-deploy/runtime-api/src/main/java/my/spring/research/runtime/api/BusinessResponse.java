package my.spring.research.runtime.api;

import java.util.Map;
import java.util.Objects;

public record BusinessResponse(String code, String message, Map<String, String> attributes) {
	public BusinessResponse {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code must not be blank");
		}
		message = Objects.requireNonNull(message, "message");
		attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
	}

	public static BusinessResponse ok(String message) {
		return new BusinessResponse("OK", message, Map.of());
	}
}
