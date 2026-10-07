package com.example.reload.fixture;

import java.util.function.Supplier;

import org.springframework.transaction.annotation.Transactional;

/**
 * 부모 소유 {@code @Transactional} bean. 자식 코드가 주입받아 호출한다. 콜백은 호출 동안만 쓰고 저장하지 않으므로
 * 이 bean 때문에 이전 세대가 붙잡히지는 않는다.
 */
public class ParentTransactionalService {

	private final ThreadBoundTransactionManager transactions;

	public ParentTransactionalService(ThreadBoundTransactionManager transactions) {
		this.transactions = transactions;
	}

	@Transactional
	public String describe() {
		return "parent=" + this.transactions.describeCurrent();
	}

	/**
	 * 부모가 연 트랜잭션 안에서 자식 콜백을 실행한다.
	 */
	@Transactional
	public String describeThenCall(Supplier<String> work) {
		return "parent=" + this.transactions.describeCurrent() + " " + work.get();
	}

	/**
	 * 부모가 연 트랜잭션 안에서 자식 콜백을 실행하고, 콜백의 실패를 삼킨 뒤 정상 반환한다.
	 */
	@Transactional
	public void callIgnoringFailure(Runnable work) {
		try {
			work.run();
		}
		catch (IllegalStateException ex) {
			// 참여한 쪽의 실패를 삼켜도 트랜잭션은 rollback-only로 남는다.
		}
	}

	@Transactional
	public void fail() {
		throw new IllegalStateException("parent failure");
	}

}
