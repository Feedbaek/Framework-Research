package com.example.reload.generation;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

import com.example.reload.fixture.GreetingService;

/**
 * 테스트용 소비자 애플리케이션(부모 context). 엔진을 의존성으로 추가한 소비자와 같은 조건이다.
 * exclude나 {@code @Import} 없이 auto-configuration만으로 엔진이 동작해야 한다. 컴포넌트 스캔은 하지 않는다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class TestHostApplication {

	@Bean
	GreetingService greetingService() {
		return (name) -> "Hello, " + name + "!";
	}

}
