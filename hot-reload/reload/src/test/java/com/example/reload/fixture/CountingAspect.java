package com.example.reload.fixture;

import java.util.concurrent.atomic.AtomicInteger;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/**
 * 부모에 등록되는 공용 aspect. 자식(재로딩 대상) 컨트롤러 호출 횟수를 센다. 자식 객체는 저장하지 않는다.
 */
@Aspect
public class CountingAspect {

	private final AtomicInteger invocations = new AtomicInteger();

	@Around("execution(* com.example.app..*Controller.*(..))")
	public Object count(ProceedingJoinPoint joinPoint) throws Throwable {
		this.invocations.incrementAndGet();
		return joinPoint.proceed();
	}

	public int invocations() {
		return this.invocations.get();
	}

}
