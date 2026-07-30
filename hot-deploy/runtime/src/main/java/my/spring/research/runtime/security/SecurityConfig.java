package my.spring.research.runtime.security;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
	@Bean
	@Order(1)
	SecurityFilterChain runtimeRefreshSecurity(HttpSecurity http) throws Exception {
		http.securityMatcher("/actuator/runtimeRefresh")
				.authorizeHttpRequests(authorize -> authorize
						.anyRequest().hasRole("RUNTIME_ADMIN"))
				.httpBasic(withDefaults())
				.csrf(csrf -> csrf.disable());
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain actuatorSecurity(HttpSecurity http) throws Exception {
		http.securityMatcher(EndpointRequest.toAnyEndpoint())
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(EndpointRequest.to("health", "info")).permitAll()
						.anyRequest().hasRole("RUNTIME_ADMIN"))
				.httpBasic(withDefaults())
				.csrf(csrf -> csrf.disable());
		return http.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(authorize -> authorize
						.requestMatchers("/api/business", "/error").permitAll()
						.anyRequest().denyAll())
				.csrf(csrf -> csrf.disable());
		return http.build();
	}
}
