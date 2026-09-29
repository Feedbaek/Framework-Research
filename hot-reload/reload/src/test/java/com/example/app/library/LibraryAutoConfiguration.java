package com.example.app.library;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 애플리케이션과 같은 패키지 트리에 있는 라이브러리 auto-configuration을 흉내 낸다. 다른 테스트에 영향을 주지
 * 않도록 {@code test.library.enabled=true}일 때만 켜진다.
 */
@AutoConfiguration
@ConditionalOnProperty("test.library.enabled")
public class LibraryAutoConfiguration {

	@Bean
	LibraryClient libraryClient() {
		return new LibraryClient();
	}

}
