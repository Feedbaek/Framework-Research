package my.spring.research.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.cloud.compatibility-verifier.enabled=false",
		"spring.security.user.password=test-only-password"
})
class RuntimeApplicationTests {

	@Test
	void contextLoads() {
	}
}
