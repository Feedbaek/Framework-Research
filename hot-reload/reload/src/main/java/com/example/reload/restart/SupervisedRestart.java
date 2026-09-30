package com.example.reload.restart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ConfigurableApplicationContext;

/** 종료 권한은 RestartLauncher가 전달한 요청 파일이 있을 때만 활성화된다. */
public final class SupervisedRestart implements FullRestart {
	private static final Log logger = LogFactory.getLog(SupervisedRestart.class);
	private final ConfigurableApplicationContext context;
	private final Path request;
	private final AtomicBoolean pending = new AtomicBoolean();

	public SupervisedRestart(ConfigurableApplicationContext context) {
		this.context = context;
		String path = System.getenv(RestartLauncher.REQUEST_FILE_ENV);
		this.request = path == null || path.isBlank() ? null : Path.of(path);
	}

	@Override
	public boolean available() { return this.request != null && Files.isDirectory(this.request.getParent()); }

	@Override
	public void request() {
		if (!available()) {
			throw new IllegalStateException("Full restart requires RestartLauncher");
		}
		if (!this.pending.compareAndSet(false, true)) { return; }
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((task) -> {
			Thread thread = new Thread(task, "reload-full-restart");
			thread.setContextClassLoader(SupervisedRestart.class.getClassLoader());
			return thread;
		});
		// API에 202 응답을 쓸 기회를 준다. 실제 재시작 완료는 새 프로세스의 readiness로 확인한다.
		executor.schedule(() -> {
			try {
				Files.writeString(this.request, "restart\n");
			}
			catch (IOException ex) {
				logger.error("Cannot signal the supervisor; application remains running", ex);
				this.pending.set(false);
				executor.shutdown();
				return;
			}
			try {
				this.context.close();
			}
			finally {
				executor.shutdown();
				System.exit(0);
			}
		}, 500, TimeUnit.MILLISECONDS);
	}
}
