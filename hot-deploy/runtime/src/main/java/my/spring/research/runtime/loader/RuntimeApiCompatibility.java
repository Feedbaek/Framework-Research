package my.spring.research.runtime.loader;

import java.util.Arrays;

final class RuntimeApiCompatibility {

	private RuntimeApiCompatibility() {
	}

	static boolean includes(String requirement, String currentVersion) {
		if (requirement == null || requirement.isBlank()) {
			return false;
		}
		String normalized = requirement.trim();
		if (normalized.endsWith(".x")) {
			return currentVersion.startsWith(normalized.substring(0, normalized.length() - 1));
		}
		if (normalized.startsWith("[") || normalized.startsWith("(")) {
			return includesRange(normalized, currentVersion);
		}
		return compare(currentVersion, normalized) == 0;
	}

	private static boolean includesRange(String range, String currentVersion) {
		if (!(range.endsWith("]") || range.endsWith(")")) || !range.contains(",")) {
			return false;
		}
		String[] limits = range.substring(1, range.length() - 1).split(",", -1);
		if (limits.length != 2) {
			return false;
		}
		int lower = limits[0].isBlank() ? 1 : compare(currentVersion, limits[0].trim());
		int upper = limits[1].isBlank() ? -1 : compare(currentVersion, limits[1].trim());
		boolean lowerMatches = limits[0].isBlank() || lower > 0 || (lower == 0 && range.startsWith("["));
		boolean upperMatches = limits[1].isBlank() || upper < 0 || (upper == 0 && range.endsWith("]"));
		return lowerMatches && upperMatches;
	}

	private static int compare(String left, String right) {
		int[] leftParts = parse(left);
		int[] rightParts = parse(right);
		for (int i = 0; i < 3; i++) {
			int compared = Integer.compare(leftParts[i], rightParts[i]);
			if (compared != 0) {
				return compared;
			}
		}
		return 0;
	}

	private static int[] parse(String value) {
		String numeric = value.split("-", 2)[0];
		String[] parts = numeric.split("\\.");
		if (parts.length < 1 || parts.length > 3
				|| Arrays.stream(parts).anyMatch(part -> !part.matches("\\d+"))) {
			throw new IllegalArgumentException("Invalid semantic version: " + value);
		}
		int[] result = new int[3];
		for (int i = 0; i < parts.length; i++) {
			result[i] = Integer.parseInt(parts[i]);
		}
		return result;
	}
}
