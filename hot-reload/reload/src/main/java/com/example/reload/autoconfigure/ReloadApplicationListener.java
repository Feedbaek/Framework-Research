package com.example.reload.autoconfigure;

import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

import com.example.reload.ReloadProperties;
import com.example.reload.layout.ReloadLayout;
import com.example.reload.layout.ReloadParentTypeExcludeFilter;

/**
 * 부모 context가 refresh되기 전에 재로딩 배치({@link ReloadLayout})를 정하고, 부모 컴포넌트 스캔에서
 * 자식 소유 클래스를 빼는 필터를 등록한다.
 * <p>
 * 컴포넌트 스캔은 refresh 초반에 일어나므로 auto-configuration으로는 늦다. {@code spring.factories}로
 * 등록되며, 애플리케이션 소스(bean 정의)가 로드된 직후인 {@link ApplicationPreparedEvent}에서 동작한다.
 */
public class ReloadApplicationListener implements ApplicationListener<ApplicationPreparedEvent> {

	@Override
	public void onApplicationEvent(ApplicationPreparedEvent event) {
		ConfigurableApplicationContext context = event.getApplicationContext();
		if (!(context instanceof WebApplicationContext)) {
			return;
		}
		ReloadProperties properties = Binder.get(context.getEnvironment())
			.bind("reload", ReloadProperties.class)
			.orElseGet(ReloadProperties::new);
		if (!properties.isEnabled()) {
			return;
		}
		ReloadLayout layout = ReloadLayout.resolve(properties, context.getBeanFactory());
		context.getBeanFactory().registerSingleton(ReloadLayout.BEAN_NAME, layout);
		if (properties.isHybrid()) {
			context.getBeanFactory()
			.registerSingleton(ReloadParentTypeExcludeFilter.BEAN_NAME, new ReloadParentTypeExcludeFilter(layout));
		}
	}

}
