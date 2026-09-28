package com.example.reload.watch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.example.reload.devtools.classpath.ClassPathRestartStrategy;
import com.example.reload.devtools.classpath.PatternClassPathRestartStrategy;
import com.example.reload.devtools.filewatch.ChangedFile;
import com.example.reload.devtools.filewatch.ChangedFiles;
import com.example.reload.devtools.filewatch.FileSystemWatcher;
import com.example.reload.layout.ClassOwnership;

/**
 * 자식 클래스패스 디렉터리를 감시하다가 제외 패턴에 걸리지 않는 변경이 있으면 재로딩을 요청한다. 부모 소유
 * 클래스의 변경은 재로딩으로 반영되지 않으므로 재시작하라는 경고만 남긴다.
 * <p>
 * 재로딩은 watcher 스레드에서 실행된다. 이 스레드의 TCCL은 부모 클래스로더여야 하므로, 스레드를
 * 만드는 {@link #start()}와 변경 콜백에서 모두 TCCL을 부모 클래스로더로 맞춘다.
 */
public class ClassPathChangeWatcher {

	private static final Log logger = LogFactory.getLog(ClassPathChangeWatcher.class);

	private final FileSystemWatcher fileSystemWatcher;

	private final ClassPathRestartStrategy restartStrategy;

	private final ClassLoader hostClassLoader;

	private final ClassOwnership ownership;

	private final Runnable reloadAction;

	public ClassPathChangeWatcher(List<Path> directories, Duration pollInterval, Duration quietPeriod,
			List<String> excludePatterns, ClassLoader hostClassLoader, ClassOwnership ownership,
			Runnable reloadAction) {
		this.fileSystemWatcher = new FileSystemWatcher(true, pollInterval, quietPeriod);
		this.restartStrategy = new PatternClassPathRestartStrategy(excludePatterns.toArray(String[]::new));
		this.hostClassLoader = hostClassLoader;
		this.ownership = ownership;
		this.reloadAction = reloadAction;
		this.fileSystemWatcher.addSourceDirectories(directories.stream().map(Path::toFile).toList());
		this.fileSystemWatcher.addListener(this::onChange);
	}

	public void start() {
		Thread thread = Thread.currentThread();
		ClassLoader previousTccl = thread.getContextClassLoader();
		// FileSystemWatcher가 만드는 스레드는 현재 스레드의 TCCL을 물려받는다.
		thread.setContextClassLoader(this.hostClassLoader);
		try {
			this.fileSystemWatcher.start();
		}
		finally {
			thread.setContextClassLoader(previousTccl);
		}
	}

	public void stop() {
		this.fileSystemWatcher.stop();
	}

	private void onChange(Set<ChangedFiles> changeSet) {
		Thread.currentThread().setContextClassLoader(this.hostClassLoader);
		List<String> parentOwnedChanges = new ArrayList<>();
		ChangedFile trigger = null;
		for (ChangedFiles changedFiles : changeSet) {
			for (ChangedFile changedFile : changedFiles) {
				if (!this.restartStrategy.isRestartRequired(changedFile)) {
					continue;
				}
				String className = ClassOwnership.classNameOf(changedFile.getRelativeName());
				if (className != null && this.ownership.isParentOwned(className)) {
					parentOwnedChanges.add(className);
				}
				else if (trigger == null) {
					trigger = changedFile;
				}
			}
		}
		if (!parentOwnedChanges.isEmpty()) {
			// 부모 소유 클래스는 부모 클래스로더가 이미 로드했으므로 재로딩으로 바뀌지 않는다.
			logger.warn("Parent-owned classes changed; restart the application to apply them: " + parentOwnedChanges);
		}
		if (trigger == null) {
			logger.debug("No reloadable changes in " + changeSet);
			return;
		}
		logger.info("Change detected (" + trigger.getType() + " " + trigger.getRelativeName() + "); reloading");
		try {
			this.reloadAction.run();
		}
		catch (RuntimeException | LinkageError ex) {
			// 예외가 watcher 스레드를 죽이면 이후 변경을 감지하지 못한다.
			logger.error("Reload failed", ex);
		}
	}

}
