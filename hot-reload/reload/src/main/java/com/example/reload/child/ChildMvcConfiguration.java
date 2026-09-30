package com.example.reload.child;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Boot의 표준 MVC 확장점을 매 세대의 인프라 생성에 반영한다(ProObject 포함). */
@Configuration(proxyBeanMethods = false)
public class ChildMvcConfiguration extends DelegatingWebMvcConfiguration {
	private final WebMvcRegistrations registrations;

	public ChildMvcConfiguration(ObjectProvider<WebMvcRegistrations> registrations) {
		this.registrations = registrations.getIfUnique();
	}

	@Override
	protected RequestMappingHandlerAdapter createRequestMappingHandlerAdapter() {
		RequestMappingHandlerAdapter adapter = this.registrations == null ? null
				: this.registrations.getRequestMappingHandlerAdapter();
		return adapter != null ? adapter : super.createRequestMappingHandlerAdapter();
	}

	@Override
	protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
		RequestMappingHandlerMapping mapping = this.registrations == null ? null
				: this.registrations.getRequestMappingHandlerMapping();
		return mapping != null ? mapping : super.createRequestMappingHandlerMapping();
	}

	@Override
	protected ExceptionHandlerExceptionResolver createExceptionHandlerExceptionResolver() {
		ExceptionHandlerExceptionResolver resolver = this.registrations == null ? null
				: this.registrations.getExceptionHandlerExceptionResolver();
		return resolver != null ? resolver : super.createExceptionHandlerExceptionResolver();
	}
}
