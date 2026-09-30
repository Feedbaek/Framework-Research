package com.example.reload.restart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 애플리케이션 프로세스 밖에서 실행하는 감독자. 셸 해석 없이 명령과 인자를 그대로 전달한다.
 * 예: java -cp reload.jar com.example.reload.restart.RestartLauncher -- ./gradlew :app:bootRun
 * 요청 파일이 있는 종료만 재시작하므로 시작 실패를 무한 재시도하지 않는다.
 */
public final class RestartLauncher {
	public static final String REQUEST_FILE_ENV = "HOT_RELOAD_RESTART_FILE";

	private RestartLauncher() { }

	public static void main(String[] args) throws Exception {
		if (args.length < 2 || !"--".equals(args[0])) {
			throw new IllegalArgumentException("Usage: RestartLauncher -- <command> [arguments...]");
		}
		System.exit(run(Arrays.asList(args).subList(1, args.length)));
	}

	static int run(List<String> command) throws IOException, InterruptedException {
		Path directory = Files.createTempDirectory("hot-reload-supervisor-");
		Path request = directory.resolve("restart.request");
		AtomicReference<Process> running = new AtomicReference<>();
		Thread shutdown = new Thread(() -> {
			Process process = running.get();
			if (process != null) {
				// 감독자가 시작한 명령과 그 자식만 종료한다.
				process.descendants().forEach(ProcessHandle::destroy);
				process.destroy();
				try {
					if (!process.waitFor(5, TimeUnit.SECONDS)) {
						process.descendants().forEach(ProcessHandle::destroyForcibly);
						process.destroyForcibly();
					}
				}
				catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
			}
		}, "reload-supervisor-stop");
		Runtime.getRuntime().addShutdownHook(shutdown);
		try {
			while (true) {
				Files.deleteIfExists(request);
				ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
				builder.environment().put(REQUEST_FILE_ENV, request.toString());
				Process process = builder.start();
				running.set(process);
				int exit = process.waitFor();
				running.set(null);
				if (!Files.isRegularFile(request)) {
					return exit;
				}
				System.out.println("[hot-reload] Infrastructure changed; restarting the application command");
			}
		}
		finally {
			Process process = running.getAndSet(null);
			if (process != null) {
				process.descendants().forEach(ProcessHandle::destroy);
				process.destroy();
			}
			Runtime.getRuntime().removeShutdownHook(shutdown);
			Files.deleteIfExists(request);
			Files.deleteIfExists(directory);
		}
	}
}
