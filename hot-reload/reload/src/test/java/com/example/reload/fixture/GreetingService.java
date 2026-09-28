package com.example.reload.fixture;

/**
 * 테스트용 공유 타입. 부모(테스트 클래스패스)가 로드하고, 런타임에 컴파일되는 자식 컨트롤러가 주입받는다.
 */
public interface GreetingService {

	String greet(String name);

}
