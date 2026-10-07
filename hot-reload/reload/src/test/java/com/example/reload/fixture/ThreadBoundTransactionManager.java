package com.example.reload.fixture;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code DataSourceTransactionManager}처럼 트랜잭션 자원을 스레드에 묶는 테스트용 부모 {@code TransactionManager}.
 * <p>
 * {@link NoOpTransactionManager}와 달리 이미 열린 트랜잭션을 알아보므로({@link #isExistingTransaction}) 참여와
 * rollback-only 전파를 검증할 수 있다. 시작한 물리 트랜잭션마다 번호를 매기고 커밋·롤백 횟수를 센다.
 */
public class ThreadBoundTransactionManager extends AbstractPlatformTransactionManager {

	private final AtomicInteger begun = new AtomicInteger();

	private final AtomicInteger committed = new AtomicInteger();

	private final AtomicInteger rolledBack = new AtomicInteger();

	/**
	 * 현재 스레드에 묶인 트랜잭션 번호. 없으면 {@code null}.
	 */
	public Integer currentTransactionId() {
		Holder holder = (Holder) TransactionSynchronizationManager.getResource(this);
		return (holder != null) ? holder.id : null;
	}

	/**
	 * 현재 스레드의 트랜잭션과, 가장 안쪽 {@code @Transactional} 메서드가 그 트랜잭션을 새로 열었는지({@code new})
	 * 참여했는지({@code joined})를 {@code tx1:new} 형태로 돌려준다. {@code @Transactional} 메서드 안에서 호출한다.
	 * @throws NoTransactionException 트랜잭션 인터셉터를 거치지 않고 호출한 경우
	 */
	public String describeCurrent() {
		boolean newTransaction = TransactionAspectSupport.currentTransactionStatus().isNewTransaction();
		return "tx" + currentTransactionId() + (newTransaction ? ":new" : ":joined");
	}

	public int begun() {
		return this.begun.get();
	}

	public int committed() {
		return this.committed.get();
	}

	public int rolledBack() {
		return this.rolledBack.get();
	}

	public void reset() {
		this.begun.set(0);
		this.committed.set(0);
		this.rolledBack.set(0);
	}

	@Override
	protected Object doGetTransaction() {
		return new TransactionObject((Holder) TransactionSynchronizationManager.getResource(this));
	}

	@Override
	protected boolean isExistingTransaction(Object transaction) {
		return ((TransactionObject) transaction).holder != null;
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		Holder holder = new Holder(this.begun.incrementAndGet());
		((TransactionObject) transaction).holder = holder;
		TransactionSynchronizationManager.bindResource(this, holder);
	}

	@Override
	protected void doCommit(DefaultTransactionStatus status) {
		this.committed.incrementAndGet();
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) {
		this.rolledBack.incrementAndGet();
	}

	@Override
	protected void doSetRollbackOnly(DefaultTransactionStatus status) {
		((TransactionObject) status.getTransaction()).holder.rollbackOnly = true;
	}

	@Override
	protected void doCleanupAfterCompletion(Object transaction) {
		TransactionSynchronizationManager.unbindResource(this);
	}

	private static final class Holder {

		private final int id;

		private volatile boolean rollbackOnly;

		private Holder(int id) {
			this.id = id;
		}

	}

	private static final class TransactionObject implements SmartTransactionObject {

		private Holder holder;

		private TransactionObject(Holder holder) {
			this.holder = holder;
		}

		@Override
		public boolean isRollbackOnly() {
			return this.holder != null && this.holder.rollbackOnly;
		}

		@Override
		public void flush() {
		}

	}

}
