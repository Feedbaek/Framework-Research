package my.spring.research.runtime.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

class BusinessRequestTest {
	@Test
	void copiesAttributes() {
		HashMap<String, String> source = new HashMap<>();
		source.put("key", "value");

		BusinessRequest request = new BusinessRequest("hello", source);
		source.put("key", "changed");

		assertEquals("value", request.attributes().get("key"));
	}

	@Test
	void rejectsBlankOperation() {
		assertThrows(IllegalArgumentException.class, () -> new BusinessRequest(" "));
	}
}
