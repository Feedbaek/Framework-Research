package com.example.reload.fixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 라이브러리 jar가 제공하는 컨트롤러(springdoc, Spring Boot Admin 등)를 흉내 낸다. 부모에 bean으로 등록된다.
 */
@RestController
public class LibraryController {

	@GetMapping("/library/info")
	public String info() {
		return "library";
	}

}
