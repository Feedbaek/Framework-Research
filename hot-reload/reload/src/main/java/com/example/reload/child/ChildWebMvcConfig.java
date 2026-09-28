package com.example.reload.child;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 모든 자식 context에 register되는 MVC 구성. 부모에는 MVC가 없다.
 * <p>
 * 이 클래스는 {@code @Configuration}이므로 소비자 애플리케이션이 {@code com.example.reload} 패키지를
 * 컴포넌트 스캔하면 안 된다. 부모에 등록되면 부모에도 MVC가 켜진다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebMvc
@EnableConfigurationProperties
public class ChildWebMvcConfig implements WebMvcConfigurer {

	private final ObjectProvider<ObjectMapper> objectMapper;

	public ChildWebMvcConfig(ObjectProvider<ObjectMapper> objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
		converters.add(new ByteArrayHttpMessageConverter());
		converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
		converters.add(new ResourceHttpMessageConverter());
		converters.add(new ResourceRegionHttpMessageConverter());
		converters.add(new AllEncompassingFormHttpMessageConverter());
		converters.add(new MappingJackson2HttpMessageConverter(childObjectMapper()));
	}

	/**
	 * 부모 {@link ObjectMapper}의 설정(모듈, feature, naming 등)을 그대로 쓰되 {@link ObjectMapper#copy()
	 * 복사본}을 쓴다. 직렬화기/역직렬화기 캐시는 {@code ObjectMapper} 인스턴스에 붙어 있어서, 부모
	 * 인스턴스를 그대로 쓰면 자식 클래스용 (역)직렬화기가 부모에 남아 이전 클래스로더를 붙잡는다.
	 * 복사본의 캐시는 세대와 함께 사라진다.
	 */
	private ObjectMapper childObjectMapper() {
		ObjectMapper parent = this.objectMapper.getIfAvailable();
		return (parent != null) ? parent.copy() : new ObjectMapper();
	}

}
