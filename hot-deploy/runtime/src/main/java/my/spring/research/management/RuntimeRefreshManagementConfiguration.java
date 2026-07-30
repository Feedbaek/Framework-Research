package my.spring.research.management;

import my.spring.research.runtime.config.RuntimeRefreshCoordinator;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.context.annotation.Bean;

@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
public class RuntimeRefreshManagementConfiguration {

	@Bean
	RuntimeRefreshController runtimeRefreshController(RuntimeRefreshCoordinator coordinator) {
		return new RuntimeRefreshController(coordinator);
	}
}
