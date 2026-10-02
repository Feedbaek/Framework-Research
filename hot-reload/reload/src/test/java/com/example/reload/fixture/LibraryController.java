package com.example.reload.fixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라이브러리 jar가 제공하는 컨트롤러(springdoc, Spring Boot Admin, ProObject 시스템 컨트롤러 등)를 흉내 낸다. 부모에
 * bean으로 등록되고 세대의 핸들러 매핑이 찾는다.
 */
@RestController
public class LibraryController {

	@GetMapping("/library/info")
	public String info() {
		return "library";
	}

}
