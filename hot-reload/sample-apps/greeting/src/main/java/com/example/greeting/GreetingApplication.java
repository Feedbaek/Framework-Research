package com.example.greeting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 평범한 Spring Boot 애플리케이션. 재로딩 엔진({@code reload})은 의존성만 추가하면 auto-configuration으로
 * 붙는다.
 * <p>
 * 이 클래스와 {@code reload.parent-packages}({@code com.example.greeting.shared})는 부모 context에 속하고
 * JVM 수명 동안 한 번만 로드된다. 나머지 클래스(예: {@code com.example.greeting.web})는 자식 세대가 로드하고
 * 바뀔 때마다 새로 올라온다. 이 클래스는 부모 소유이므로 자식 패키지의 타입을 참조하면 안 된다.
 */
@SpringBootApplication
public class GreetingApplication {

	public static void main(String[] args) {
		SpringApplication.run(GreetingApplication.class, args);
	}

}
