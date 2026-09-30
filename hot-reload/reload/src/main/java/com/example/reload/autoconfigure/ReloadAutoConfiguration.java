package com.example.reload.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.example.reload.restart.FullRestart;
import com.example.reload.restart.SupervisedRestart;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import com.example.reload.ReloadProperties;
import com.example.reload.generation.GenerationCacheCleaner;
import com.example.reload.generation.GenerationManager;
import com.example.reload.generation.ReloadTriggerServlet;
import com.example.reload.generation.ReloadingDispatcherServlet;
import com.example.reload.layout.ReloadLayout;

/**
 * 재로딩 엔진 auto-configuration. 소비자 애플리케이션(부모 context)에 엔진 bean과
 * {@link ReloadingDispatcherServlet}(`/`)을 등록한다.
 * <p>
 * 부모에서는 MVC와 에러 처리를 끄고 자식 세대가 담당한다. 이를 위한 auto-configuration 제외는
 * {@link ReloadAutoConfigurationImportFilter}가 하므로 소비자가 따로 exclude할 필요가 없다.
 * {@code reload.enabled=false}이면 둘 다 꺼지고 평범한 Spring MVC 애플리케이션으로 동작한다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnProperty(prefix = "reload", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ReloadProperties.class)
public class ReloadAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(FullRestart.class)
	FullRestart fullRestart(ConfigurableApplicationContext context) {
		return new SupervisedRestart(context);
	}

	@Bean
	GenerationManager generationManager(ReloadProperties properties, ConfigurableApplicationContext applicationContext,
			ObjectProvider<ObjectMapper> objectMapper, FullRestart fullRestart) {
		// 보통은 ReloadApplicationListener가 refresh 전에 등록해 둔다(부모 스캔 필터와 같은 인스턴스).
		ReloadLayout layout = applicationContext.getBeanFactory().containsSingleton(ReloadLayout.BEAN_NAME)
				? (ReloadLayout) applicationContext.getBeanFactory().getSingleton(ReloadLayout.BEAN_NAME)
				: ReloadLayout.resolve(properties, applicationContext.getBeanFactory());
		return new GenerationManager(properties, layout, applicationContext,
				new GenerationCacheCleaner(objectMapper), fullRestart);
	}

	@Bean
	@Conditional(OnHybridCondition.class)
	ServletRegistrationBean<ReloadingDispatcherServlet> reloadingDispatcherServlet(
			GenerationManager generationManager) {
		ServletRegistrationBean<ReloadingDispatcherServlet> registration = new ServletRegistrationBean<>(
				new ReloadingDispatcherServlet(generationManager), "/");
		registration.setName("reloadingDispatcherServlet");
		registration.setLoadOnStartup(1);
		registration.setAsyncSupported(true);
		return registration;
	}

	/**
	 * 재로딩 API. 정확한 경로 매핑이라 `/`에 매핑된 {@link ReloadingDispatcherServlet}보다 우선한다.
	 */
	@Bean
	@Conditional(OnReloadApiCondition.class)
	ServletRegistrationBean<ReloadTriggerServlet> reloadTriggerServlet(GenerationManager generationManager,
			ReloadProperties properties) {
		ServletRegistrationBean<ReloadTriggerServlet> registration = new ServletRegistrationBean<>(
				new ReloadTriggerServlet(generationManager), properties.getTrigger().getApiPath());
		registration.setName("reloadTriggerServlet");
		return registration;
	}

}
