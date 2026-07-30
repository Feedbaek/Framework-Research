package my.spring.research.runtime.config;

import my.spring.research.runtime.api.RuntimeMessageProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({ HotDeployProperties.class, RuntimeMessageProperties.class })
public class RuntimeConfig {
	@Bean
	@RefreshScope
	RuntimeMessageProvider runtimeMessageProvider(RuntimeMessageProperties properties) {
		return operation -> properties.getPrefix() + operation;
	}
}
