package com.example.reload.fixture;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * 일반 Spring Boot 애플리케이션이라면 통과해야 하지만 현재 엔진에서는 실패하는 시나리오. 기본 {@code test}
 * 태스크에서는 빠지고 {@code knownIssueTest} 태스크로 돌린다. 엔진이 고쳐지면 이 애너테이션을 지운다.
 * <p>
 * {@link #value()}에는 실패 원인과 {@code docs/reload-aop-proxy-risks.md}의 해당 장을 적는다.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("known-issue")
public @interface KnownIssue {

	String value();

}
