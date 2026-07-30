package my.spring.research.runtime.deploy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import my.spring.research.runtime.config.HotDeployProperties;
import my.spring.research.runtime.loader.CandidateContextFactory;
import my.spring.research.runtime.loader.LoadedCandidate;

@Service
public class DeploymentManager {

	private final CandidateContextFactory candidateContextFactory;
	private final DeploymentRouter deploymentRouter;
	private final DeploymentControlPlane controlPlane;
	private final DrainManager drainManager;
	private final HotDeployProperties properties;
	private final List<DeploymentSlot> retained = new ArrayList<>();
	private final List<DeploymentSlot> unloading = new ArrayList<>();
	private final List<DeploymentStatus> failed = new ArrayList<>();
	private final Map<String, Path> artifactHistory = new LinkedHashMap<>();

	@Autowired
	public DeploymentManager(
			CandidateContextFactory candidateContextFactory,
			DeploymentRouter deploymentRouter,
			DeploymentControlPlane controlPlane,
			DrainManager drainManager,
			HotDeployProperties properties) {
		this.candidateContextFactory = candidateContextFactory;
		this.deploymentRouter = deploymentRouter;
		this.controlPlane = controlPlane;
		this.drainManager = drainManager;
		this.properties = properties;
	}

	public DeploymentStatus deploy(Path artifactPath) {
		if (candidateContextFactory == null) {
			throw new DeploymentRejectedException("Artifact deployment is not available in this manager");
		}
		if (!controlPlane.tryAcquire()) {
			throw new DeploymentBusyException("control plane is busy");
		}
		LoadedCandidate candidate = null;
		try {
			candidate = candidateContextFactory.load(artifactPath);
			DeploymentStatus status = activateLoadedCandidate(candidate);
			candidate = null;
			return status;
		}
		finally {
			if (candidate != null) {
				candidate.close();
			}
			controlPlane.release();
		}
	}

	public DeploymentStatus activate(LoadedCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate");
		if (!controlPlane.tryAcquire()) {
			throw new DeploymentBusyException("control plane is busy");
		}
		boolean transferred = false;
		try {
			DeploymentStatus result = activateLoadedCandidate(candidate);
			transferred = true;
			return result;
		}
		finally {
			if (!transferred) {
				candidate.close();
			}
			controlPlane.release();
		}
	}

	DeploymentStatus activateSlot(DeploymentSlot slot) {
		Objects.requireNonNull(slot, "slot");
		if (!controlPlane.tryAcquire()) {
			throw new DeploymentBusyException("control plane is busy");
		}
		try {
			return activateSlotLocked(slot);
		}
		finally {
			controlPlane.release();
		}
	}

	public DeploymentStatus rollback(String deploymentId) {
		if (!controlPlane.tryAcquire()) {
			throw new DeploymentBusyException("control plane is busy");
		}
		try {
			pruneExpiredRetainedLocked();
			DeploymentSlot retainedSlot = retained.stream()
					.filter(slot -> DeploymentStatus.from(slot).deploymentId().equals(deploymentId))
					.findFirst()
					.orElse(null);
			if (retainedSlot != null) {
				retained.remove(retainedSlot);
				DeploymentSlot old = deploymentRouter.activateSlot(retainedSlot);
				if (old != null && old != retainedSlot) {
					handlePrevious(old);
				}
				return DeploymentStatus.from(retainedSlot);
			}
			Path artifactPath = artifactHistory.get(deploymentId);
			if (artifactPath == null || candidateContextFactory == null) {
				throw new DeploymentRejectedException("Deployment is not available for rollback: " + deploymentId);
			}
			LoadedCandidate reloaded = candidateContextFactory.load(artifactPath);
			try {
				return activateLoadedCandidate(reloaded);
			}
			catch (RuntimeException ex) {
				reloaded.close();
				throw ex;
			}
		}
		finally {
			controlPlane.release();
		}
	}

	public HotDeployStatus status() {
		controlPlane.acquire();
		try {
			pruneExpiredRetainedLocked();
			return new HotDeployStatus(
					deploymentRouter.activeSlot().map(DeploymentStatus::from).orElse(DeploymentStatus.empty()),
					retained.stream().map(DeploymentStatus::from).toList(),
					unloading.stream().map(DeploymentStatus::from).toList(),
					List.copyOf(failed));
		}
		finally {
			controlPlane.release();
		}
	}

	@Scheduled(fixedDelayString = "${runtime.hot-deploy.cleanup-interval:5s}")
	public void scheduledCleanup() {
		if (!controlPlane.tryAcquire()) {
			return;
		}
		try {
			pruneExpiredRetainedLocked();
			for (DeploymentSlot slot : unloading) {
				drainManager.checkDrainTimeout(slot, properties.getDrainTimeout());
			}
		}
		finally {
			controlPlane.release();
		}
	}

	public void pruneExpiredRetained() {
		controlPlane.acquire();
		try {
			pruneExpiredRetainedLocked();
		}
		finally {
			controlPlane.release();
		}
	}

	private void pruneExpiredRetainedLocked() {
		for (DeploymentSlot slot : List.copyOf(retained)) {
			if (slot.state() == DeploymentState.RETAINED_FOR_ROLLBACK && slot.retentionExpired()) {
				retained.remove(slot);
				watchForUnload(slot);
				drainManager.expireRetention(slot);
				if (slot.state() != DeploymentState.UNLOADED) {
					unloading.add(slot);
				}
			}
		}
		pruneClosedUnloading();
	}

	private DeploymentStatus activateLoadedCandidate(LoadedCandidate candidate) {
		DeploymentStatus status = activateSlotLocked(new DeploymentSlot(candidate));
		recordArtifact(status.deploymentId(), candidate.artifact().path());
		return status;
	}

	private void recordArtifact(String deploymentId, Path artifactPath) {
		artifactHistory.remove(deploymentId);
		artifactHistory.put(deploymentId, artifactPath);
		while (artifactHistory.size() > properties.getMaxArtifactHistory()) {
			String oldestDeploymentId = artifactHistory.keySet().iterator().next();
			artifactHistory.remove(oldestDeploymentId);
		}
	}

	private DeploymentStatus activateSlotLocked(DeploymentSlot slot) {
		rejectIfPendingUnloadExists();
		validatePolicy();
		DeploymentSlot old = deploymentRouter.activateSlot(slot);
		if (old != null) {
			handlePrevious(old);
		}
		pruneExpiredRetainedLocked();
		return DeploymentStatus.from(slot);
	}

	private void handlePrevious(DeploymentSlot old) {
		if (properties.getRollbackRetentionCount() > 0) {
			drainManager.drainOrRetain(old, properties.getDrainTimeout(), properties.getRollbackRetentionTtl(), true);
			retained.add(old);
			enforceRetentionLimit();
			return;
		}
		watchForUnload(old);
		if (!drainManager.drainOrRetain(old, properties.getDrainTimeout(), properties.getRollbackRetentionTtl(), false)) {
			unloading.add(old);
		}
	}

	private void enforceRetentionLimit() {
		int max = properties.getRollbackRetentionCount();
		if (max < 0) {
			throw new DeploymentRejectedException("rollbackRetentionCount must be >= 0");
		}
		retained.stream()
				.sorted(Comparator.comparing(DeploymentSlot::createdAt))
				.limit(Math.max(0, retained.size() - max))
				.toList()
				.forEach(slot -> {
					retained.remove(slot);
					watchForUnload(slot);
					if (!drainManager.unloadIfIdle(slot)) {
						unloading.add(slot);
					}
				});
	}

	private void rejectIfPendingUnloadExists() {
		pruneClosedUnloading();
		boolean hasPendingUnload = unloading.stream().anyMatch(slot -> slot.state() != DeploymentState.UNLOADED);
		if (hasPendingUnload) {
			throw new DeploymentRejectedException("Previous deployment is still waiting for active leases to drain");
		}
	}

	private void validatePolicy() {
		if (properties.getRollbackRetentionCount() < 0) {
			throw new DeploymentRejectedException("rollbackRetentionCount must be >= 0");
		}
		if (properties.getMaxArtifactHistory() < 0) {
			throw new DeploymentRejectedException("maxArtifactHistory must be >= 0");
		}
		if (properties.getDrainTimeout() == null || properties.getDrainTimeout().isNegative()
				|| properties.getDrainTimeout().isZero()) {
			throw new DeploymentRejectedException("drainTimeout must be positive");
		}
		if (properties.getRollbackRetentionTtl() == null || properties.getRollbackRetentionTtl().isNegative()
				|| properties.getRollbackRetentionTtl().isZero()) {
			throw new DeploymentRejectedException("rollbackRetentionTtl must be positive");
		}
	}

	private void pruneClosedUnloading() {
		unloading.removeIf(slot -> slot.state() == DeploymentState.UNLOADED);
	}

	private void watchForUnload(DeploymentSlot slot) {
		slot.onUnloaded(unloadedSlot -> {
			controlPlane.acquire();
			try {
				unloading.remove(unloadedSlot);
			}
			finally {
				controlPlane.release();
			}
		});
	}

	@PreDestroy
	void shutdown() {
		controlPlane.acquire();
		try {
			List<DeploymentSlot> slots = new ArrayList<>();
			DeploymentSlot active = deploymentRouter.deactivate();
			if (active != null) {
				slots.add(active);
			}
			slots.addAll(retained);
			slots.addAll(unloading);
			retained.clear();
			unloading.clear();
			slots.stream().distinct().forEach(DeploymentSlot::close);
		}
		finally {
			controlPlane.release();
		}
	}
}
