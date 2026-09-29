package com.example.reload.fixture;

/**
 * 부모 소유 서비스가 발행하는 도메인 이벤트.
 */
public record SimpleDomainEvent(String name) implements DomainEvent {

}
