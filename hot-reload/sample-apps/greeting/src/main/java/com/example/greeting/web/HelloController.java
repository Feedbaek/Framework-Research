package com.example.greeting.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.greeting.shared.GreetingService;

/**
 * 재로딩 대상 컨트롤러. {@code message}를 바꾸고 저장하면 Tomcat 재시작 없이 반영된다.
 */
@RestController
public class HelloController {

	private final GreetingService greetingService;

	public HelloController(GreetingService greetingService) {
		this.greetingService = greetingService;
	}

	@GetMapping("/hello")
	public Map<String, Object> hello(@RequestParam(defaultValue = "world") String name) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", "v1");
		body.put("greeting", this.greetingService.greet(name));
		body.put("loader", loaderIdentity());
		body.put("sharedBean", Integer.toHexString(System.identityHashCode(this.greetingService)));
		return body;
	}

	/**
	 * drain 동작 확인용. 요청 도중 코드를 바꿔도 이 요청은 이전 세대에서 끝까지 처리된다.
	 */
	@GetMapping("/slow")
	public Map<String, Object> slow(@RequestParam(defaultValue = "3000") long ms) throws InterruptedException {
		Thread.sleep(ms);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("message", "v1");
		body.put("sleptMs", ms);
		body.put("loader", loaderIdentity());
		return body;
	}

	private static String loaderIdentity() {
		ClassLoader loader = HelloController.class.getClassLoader();
		return loader.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(loader));
	}

}
