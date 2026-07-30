package my.spring.research.runtime.deploy;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class DeploymentControlPlane {
	private final ReentrantLock lock = new ReentrantLock();

	public boolean tryAcquire() {
		return lock.tryLock();
	}

	public void acquire() {
		lock.lock();
	}

	public void release() {
		lock.unlock();
	}
}
