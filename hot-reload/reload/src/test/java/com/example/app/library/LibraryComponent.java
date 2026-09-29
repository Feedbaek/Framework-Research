package com.example.app.library;

import org.springframework.stereotype.Component;

/**
 * 애플리케이션과 같은 패키지 트리({@code com.example.app})를 쓰는 라이브러리 jar의 컴포넌트를 흉내 낸다. 테스트
 * 클래스패스에 있으므로 부모 클래스패스에는 있지만 자식 클래스패스 디렉터리 밖이다.
 */
@Component
public class LibraryComponent {

}
