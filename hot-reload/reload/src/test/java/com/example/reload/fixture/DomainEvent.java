package com.example.reload.fixture;

/**
 * 부모 소유 이벤트 타입. 부모 서비스가 발행하는 이벤트와, 자식이 발행하고 부모가 받는 이벤트에 쓴다.
 */
public interface DomainEvent {

	String name();

}
