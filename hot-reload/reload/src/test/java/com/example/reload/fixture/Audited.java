package com.example.reload.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 부모 소유 애너테이션. 부모의 사용자 정의 {@code Advisor}가 이 애너테이션이 붙은 메서드를 가로챈다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

}
