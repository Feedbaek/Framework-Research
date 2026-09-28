package com.example.reload.autoconfigure;

import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ReloadAutoConfigurationImportFilterTest {

	private static final String[] CANDIDATES = {
			"org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration",
			"org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
			"org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration",
			"org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration", null };

	@Test
	void excludesParentMvcAutoConfigurationsByDefault() {
		ReloadAutoConfigurationImportFilter filter = new ReloadAutoConfigurationImportFilter();
		filter.setEnvironment(new MockEnvironment());

		assertThat(filter.match(CANDIDATES, null)).containsExactly(false, false, false, true, true);
	}

	@Test
	void keepsEverythingWhenReloadIsDisabled() {
		ReloadAutoConfigurationImportFilter filter = new ReloadAutoConfigurationImportFilter();
		filter.setEnvironment(new MockEnvironment().withProperty("reload.enabled", "false"));

		assertThat(filter.match(CANDIDATES, null)).containsExactly(true, true, true, true, true);
	}

}
