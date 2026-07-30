package my.spring.research.runtime.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "runtime.hot-deploy")
public class HotDeployProperties {

	private Path repositoryRoot = Path.of("deployments", "repository");

	private Duration drainTimeout = Duration.ofSeconds(30);

	private int rollbackRetentionCount = 1;

	private Duration rollbackRetentionTtl = Duration.ofMinutes(5);

	private int maxArtifactHistory = 100;

	private long maxArtifactBytes = 20L * 1024L * 1024L;

	private int maxJarEntries = 2_000;

	private long maxUncompressedBytes = 50L * 1024L * 1024L;

	private String runtimeApiVersion = "0.0.1";

	private Set<String> allowedClassPrefixes = new LinkedHashSet<>(Set.of("my/spring/research/app/"));

	private Set<String> refreshAllowlist = new LinkedHashSet<>(Set.of(
			"runtime.message.prefix",
			"runtime.hot-deploy.drain-timeout",
			"runtime.hot-deploy.rollback-retention-count",
			"runtime.hot-deploy.rollback-retention-ttl"
	));

	public Path getRepositoryRoot() {
		return repositoryRoot;
	}

	public void setRepositoryRoot(Path repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
	}

	public Duration getDrainTimeout() {
		return drainTimeout;
	}

	public void setDrainTimeout(Duration drainTimeout) {
		this.drainTimeout = drainTimeout;
	}

	public int getRollbackRetentionCount() {
		return rollbackRetentionCount;
	}

	public void setRollbackRetentionCount(int rollbackRetentionCount) {
		this.rollbackRetentionCount = rollbackRetentionCount;
	}

	public Duration getRollbackRetentionTtl() {
		return rollbackRetentionTtl;
	}

	public void setRollbackRetentionTtl(Duration rollbackRetentionTtl) {
		this.rollbackRetentionTtl = rollbackRetentionTtl;
	}

	public int getMaxArtifactHistory() {
		return maxArtifactHistory;
	}

	public void setMaxArtifactHistory(int maxArtifactHistory) {
		this.maxArtifactHistory = maxArtifactHistory;
	}

	public long getMaxArtifactBytes() {
		return maxArtifactBytes;
	}

	public void setMaxArtifactBytes(long maxArtifactBytes) {
		this.maxArtifactBytes = maxArtifactBytes;
	}

	public int getMaxJarEntries() {
		return maxJarEntries;
	}

	public void setMaxJarEntries(int maxJarEntries) {
		this.maxJarEntries = maxJarEntries;
	}

	public long getMaxUncompressedBytes() {
		return maxUncompressedBytes;
	}

	public void setMaxUncompressedBytes(long maxUncompressedBytes) {
		this.maxUncompressedBytes = maxUncompressedBytes;
	}

	public String getRuntimeApiVersion() {
		return runtimeApiVersion;
	}

	public void setRuntimeApiVersion(String runtimeApiVersion) {
		this.runtimeApiVersion = runtimeApiVersion;
	}

	public Set<String> getAllowedClassPrefixes() {
		return allowedClassPrefixes;
	}

	public void setAllowedClassPrefixes(Set<String> allowedClassPrefixes) {
		this.allowedClassPrefixes = allowedClassPrefixes;
	}

	public Set<String> getRefreshAllowlist() {
		return refreshAllowlist;
	}

	public void setRefreshAllowlist(Set<String> refreshAllowlist) {
		this.refreshAllowlist = refreshAllowlist;
	}
}
