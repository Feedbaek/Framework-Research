package my.spring.research.runtime.deploy;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Component;

import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.BusinessResponse;
import my.spring.research.runtime.loader.LoadedCandidate;
@Component
public class DeploymentRouter {

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private DeploymentSlot active;

	public DeploymentRouter() {
	}

	public BusinessResponse route(BusinessRequest request) {
		try (DeploymentLease lease = acquireLease()) {
			return lease.handler().handle(request);
		}
	}

	public DeploymentSlot activate(LoadedCandidate candidate) {
		DeploymentSlot previous = activateSlot(new DeploymentSlot(candidate));
		if (previous != null) {
			previous.close();
		}
		return previous;
	}

	DeploymentSlot activateSlot(DeploymentSlot next) {
		lock.writeLock().lock();
		try {
			DeploymentSlot old = active;
			next.activate();
			active = next;
			if (old != null) {
				old.drain();
			}
			return old;
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	DeploymentSlot deactivate() {
		lock.writeLock().lock();
		try {
			DeploymentSlot previous = active;
			active = null;
			if (previous != null) {
				previous.drain();
			}
			return previous;
		}
		finally {
			lock.writeLock().unlock();
		}
	}

	public Optional<DeploymentSlot> activeSlot() {
		lock.readLock().lock();
		try {
			return Optional.ofNullable(active);
		}
		finally {
			lock.readLock().unlock();
		}
	}

	public DeploymentLease acquireLease() {
		lock.readLock().lock();
		try {
			if (active == null) {
				throw new NoActiveDeploymentException();
			}
			return active.acquireLease();
		}
		finally {
			lock.readLock().unlock();
		}
	}
}
