package my.spring.research.runtime.api;

import java.util.Map;
import java.util.Objects;

public record BusinessRequest(String operation, Map<String, String> attributes) {
	public BusinessRequest {
		if (operation == null || operation.isBlank()) {
			throw new IllegalArgumentException("operation must not be blank");
		}
		attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
	}

	public BusinessRequest(String operation) {
		this(operation, Map.of());
	}
}
