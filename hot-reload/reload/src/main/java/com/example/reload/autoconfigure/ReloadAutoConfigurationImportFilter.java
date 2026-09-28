package com.example.reload.autoconfigure;

import java.util.Set;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * 재로딩 엔진이 켜져 있으면 부모 context에서 MVC 관련 auto-configuration을 제외한다.
 * <p>
 * 부모에 {@code DispatcherServlet}이나 MVC 인프라가 있으면
 * {@link com.example.reload.generation.ReloadingDispatcherServlet}과 `/` 매핑이 충돌하고, 에러 처리도 부모가
 * 가로챈다. 소비자가 {@code @SpringBootApplication(exclude = ...)}를 직접 쓰지 않아도 되도록 엔진 쪽에서 걸러 낸다.
 */
public class ReloadAutoConfigurationImportFilter implements AutoConfigurationImportFilter, EnvironmentAware {

	static final Set<String> EXCLUDED = Set.of(
			"org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration",
			"org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
			"org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration");

	private boolean enabled = true;

	@Override
	public void setEnvironment(Environment environment) {
		this.enabled = environment.getProperty("reload.enabled", Boolean.class, true);
	}

	@Override
	public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
		boolean[] matches = new boolean[autoConfigurationClasses.length];
		for (int i = 0; i < autoConfigurationClasses.length; i++) {
			String candidate = autoConfigurationClasses[i];
			matches[i] = !this.enabled || candidate == null || !EXCLUDED.contains(candidate);
		}
		return matches;
	}

}
