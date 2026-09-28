package com.example.greeting.shared;

/**
 * 부모가 구현하고 재로딩 대상 코드가 주입받아 쓰는 공유 서비스.
 * <p>
 * 부모 소유 패키지({@code reload.parent-packages})에 있으므로 부모 클래스로더만 로드한다. 자식 세대도 같은
 * {@code Class}를 보므로 부모 bean을 주입받을 수 있다.
 */
public interface GreetingService {

	String greet(String name);

}
