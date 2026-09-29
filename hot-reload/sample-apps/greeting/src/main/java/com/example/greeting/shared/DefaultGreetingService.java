package com.example.greeting.shared;

import org.springframework.stereotype.Service;

/**
 * 부모 bean. 모든 세대의 자식 context가 이 인스턴스 하나를 주입받는다.
 * <p>
 * 부모 bean은 자식 객체(자식 클래스로더가 로드한 클래스의 인스턴스, {@code Class}, 람다 등)를
 * 필드나 캐시에 붙잡으면 안 된다. 붙잡으면 이전 세대의 클래스로더가 수거되지 않는다.
 */
@Service
public class DefaultGreetingService implements GreetingService {

	@Override
	public String greet(String name) {
		return "Hello, " + name + "!";
	}

}
