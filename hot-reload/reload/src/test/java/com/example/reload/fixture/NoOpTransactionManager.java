package com.example.reload.fixture;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 자원 없이 트랜잭션 경계만 만드는 테스트용 부모 {@code TransactionManager}.
 */
public class NoOpTransactionManager extends AbstractPlatformTransactionManager {

	@Override
	protected Object doGetTransaction() {
		return new Object();
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
	}

	@Override
	protected void doCommit(DefaultTransactionStatus status) {
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) {
	}

}
