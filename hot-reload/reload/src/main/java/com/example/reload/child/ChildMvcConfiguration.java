package com.example.reload.child;

import java.lang.reflect.Field;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ApplicationObjectSupport;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Boot의 표준 MVC 확장점을 매 세대의 인프라 생성에 반영한다(ProObject 포함).
 * <p>
 * 세대의 MVC 인프라는 부모의 컨트롤러와 {@code @ControllerAdvice}도 찾는다. Spring은 이때 조상의 모든 빈 이름을
 * 제곱 시간에 합치므로({@link AncestorLookup} 참고), 엔진이 끼어들 수 있는 곳에서는 {@link AncestorLookup} 뷰로
 * 찾게 한다. 끼어들 수 없으면(예: {@code WebMvcRegistrations}가 직접 만든 매핑·리졸버) Spring의 기본 경로를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
public class ChildMvcConfiguration extends DelegatingWebMvcConfiguration {
	private final WebMvcRegistrations registrations;

	public ChildMvcConfiguration(ObjectProvider<WebMvcRegistrations> registrations) {
		this.registrations = registrations.getIfUnique();
	}

	private static ApplicationContext ancestorLookup(ApplicationContext context) {
		return (context instanceof WebApplicationContext web) ? AncestorLookup.of(web, WebApplicationContext.class)
				: AncestorLookup.of(context, ApplicationContext.class);
	}

	@Override
	protected RequestMappingHandlerAdapter createRequestMappingHandlerAdapter() {
		RequestMappingHandlerAdapter adapter = this.registrations == null ? null
				: this.registrations.getRequestMappingHandlerAdapter();
		return adapter != null ? adapter : super.createRequestMappingHandlerAdapter();
	}

	@Bean
	static ControllerAdviceLookupPostProcessor controllerAdviceLookupPostProcessor() {
		return new ControllerAdviceLookupPostProcessor();
	}

	/**
	 * 부모에는 MVC가 없으므로 부모에 등록된 컨트롤러(라이브러리가 제공하는 컨트롤러, {@code parent-packages}의 컨트롤러,
	 * 예: ProObject {@code /proobject/system/HealthStatus})도 세대의 매핑이 찾는다. 세대가 부모 bean을 참조하는
	 * 방향이므로 이전 세대를 붙잡지 않는다.
	 */
	@Override
	protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
		RequestMappingHandlerMapping mapping = this.registrations == null ? null
				: this.registrations.getRequestMappingHandlerMapping();
		if (mapping == null) {
			return new AncestorAwareRequestMappingHandlerMapping();
		}
		mapping.setDetectHandlerMethodsInAncestorContexts(true);
		return mapping;
	}

	@Override
	protected ExceptionHandlerExceptionResolver createExceptionHandlerExceptionResolver() {
		ExceptionHandlerExceptionResolver resolver = this.registrations == null ? null
				: this.registrations.getExceptionHandlerExceptionResolver();
		return resolver != null ? resolver : new AncestorAwareExceptionHandlerExceptionResolver();
	}

	/** {@code detectHandlerMethodsInAncestorContexts}와 같은 후보를 {@link AncestorLookup}으로 구한다. */
	private static final class AncestorAwareRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

		@Override
		protected String[] getCandidateBeanNames() {
			return ancestorLookup(obtainApplicationContext()).getBeanNamesForType(Object.class);
		}

	}

	/** {@code @ControllerAdvice} 탐색({@code ControllerAdviceBean.findAnnotatedBeans})이 뷰를 쓰게 한다. */
	private static final class AncestorAwareExceptionHandlerExceptionResolver extends ExceptionHandlerExceptionResolver {

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) {
			super.setApplicationContext((applicationContext != null) ? ancestorLookup(applicationContext) : null);
		}

	}

	/**
	 * {@link RequestMappingHandlerAdapter}가 초기화되는 동안만 그 context를 {@link AncestorLookup} 뷰로 바꾼다.
	 * <p>
	 * 어댑터는 {@code WebMvcRegistrations}가 만든 하위 클래스일 수 있고(ProObject), {@code @ControllerAdvice} 탐색은
	 * private 초기화 안에서 {@code getApplicationContext()}로 일어난다. context를 한 번 정하면 바꾸는 공개 API가 없어
	 * {@link ApplicationObjectSupport}의 필드를 리플렉션으로 바꿨다가 초기화가 끝나면 되돌린다. 필드를 찾지 못하면
	 * (Spring 버전 차이) 아무것도 하지 않고 Spring의 기본 경로를 쓴다.
	 */
	static final class ControllerAdviceLookupPostProcessor implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(ControllerAdviceLookupPostProcessor.class);

		private final Field contextField = findContextField();

		private static Field findContextField() {
			try {
				Field field = ReflectionUtils.findField(ApplicationObjectSupport.class, "applicationContext",
						ApplicationContext.class);
				if (field != null) {
					ReflectionUtils.makeAccessible(field);
				}
				return field;
			}
			catch (RuntimeException ex) {
				logger.debug("Cannot access ApplicationObjectSupport.applicationContext", ex);
				return null;
			}
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if (this.contextField != null && bean instanceof RequestMappingHandlerAdapter adapter
					&& adapter.getApplicationContext() != null) {
				ReflectionUtils.setField(this.contextField, adapter, ancestorLookup(adapter.getApplicationContext()));
			}
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (this.contextField != null && bean instanceof RequestMappingHandlerAdapter adapter
					&& adapter.getApplicationContext() != null
					&& java.lang.reflect.Proxy.isProxyClass(adapter.getApplicationContext().getClass())
					&& java.lang.reflect.Proxy.getInvocationHandler(adapter.getApplicationContext())
							instanceof AncestorLookup lookup) {
				ReflectionUtils.setField(this.contextField, adapter, lookup.target());
			}
			return bean;
		}

	}

}
