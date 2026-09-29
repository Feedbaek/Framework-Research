package com.example.reload.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;

/**
 * 부모에 {@code @Bean}으로 등록하는 사용자 정의 컨버터({@code text/x-reverse}). 일반 Boot 앱에서는
 * {@code HttpMessageConverters}가 이런 bean을 MVC에 추가한다.
 */
public class ReverseTextHttpMessageConverter extends AbstractHttpMessageConverter<String> {

	public static final MediaType MEDIA_TYPE = MediaType.parseMediaType("text/x-reverse");

	public ReverseTextHttpMessageConverter() {
		super(StandardCharsets.UTF_8, MEDIA_TYPE);
	}

	@Override
	protected boolean supports(Class<?> clazz) {
		return String.class == clazz;
	}

	@Override
	protected String readInternal(Class<? extends String> clazz, HttpInputMessage inputMessage) throws IOException {
		return new StringBuilder(new String(inputMessage.getBody().readAllBytes(), StandardCharsets.UTF_8)).reverse()
			.toString();
	}

	@Override
	protected void writeInternal(String value, HttpOutputMessage outputMessage) throws IOException {
		outputMessage.getBody().write(new StringBuilder(value).reverse().toString().getBytes(StandardCharsets.UTF_8));
	}

}
