package my.spring.research.runtime.config;

import java.util.Set;

public record RuntimeRefreshResult(boolean changed, Set<String> changedKeys, String message) {
}
