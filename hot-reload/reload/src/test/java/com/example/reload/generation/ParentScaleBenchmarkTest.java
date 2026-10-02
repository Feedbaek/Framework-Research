package com.example.reload.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모 빈이 많을 때 세대 교체 시간을 재는 수동 벤치마크. 환경 변수 {@code RELOAD_SCALE_BEANS}(부모 빈 수)를 준
 * 경우에만 실행한다. {@code RELOAD_SCALE_SAMPLE}까지 주면 교체 중인 스레드의 스택을 샘플링해 상위 호출 경로를
 * 출력한다.
 * <pre>
 * RELOAD_SCALE_BEANS=20000 ./gradlew :reload:test --rerun --tests '*ParentScaleBenchmarkTest' -i
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RELOAD_SCALE_BEANS", matches = "\\d+")
class ParentScaleBenchmarkTest {

	private static final int RELOADS = 5;

	@Test
	void reloadWithManyParentBeans() throws Exception {
		int beans = Integer.parseInt(System.getenv("RELOAD_SCALE_BEANS"));
		Map<String, String> sources = new LinkedHashMap<>();
		for (int i = 0; i < beans; i++) {
			String name = "Parent%05d".formatted(i);
			sources.put(ConsumerApp.PARENT_PACKAGE + "." + name, """
					package com.example.app.domain;
					@org.springframework.stereotype.Service
					public class %s {
						public String name() { return "%s"; }
					}
					""".formatted(name, name));
		}
		sources.put("com.example.app.web.ScaleController", """
				package com.example.app.web;
				@org.springframework.web.bind.annotation.RestController
				public class ScaleController {
					private final com.example.app.domain.Parent00000 parent;
					public ScaleController(com.example.app.domain.Parent00000 parent) { this.parent = parent; }
					@org.springframework.web.bind.annotation.GetMapping("/scale")
					public String value() { return parent.name(); }
				}
				""");
		long started = System.nanoTime();
		try (ConsumerApp app = ConsumerApp.start(sources, List.of("--reload.business-packages=com.example.app.web"))) {
			System.out.println("[scale] parent beans=" + beans + ", compile+start=" + millisSince(started) + " ms");
			assertThat(app.getOk("/scale")).isEqualTo("Parent00000");
			Map<String, Integer> samples = new ConcurrentHashMap<>();
			AtomicBoolean sampling = new AtomicBoolean(System.getenv("RELOAD_SCALE_SAMPLE") != null);
			Thread worker = Thread.currentThread();
			Thread sampler = new Thread(() -> {
				while (sampling.get()) {
					StringBuilder key = new StringBuilder();
					int kept = 0;
					for (StackTraceElement frame : worker.getStackTrace()) {
						if (kept < 14) {
							String type = frame.getClassName();
							key.append("\n      ").append(type.substring(type.lastIndexOf('.') + 1)).append('.')
								.append(frame.getMethodName());
							kept++;
						}
					}
					samples.merge(key.toString(), 1, Integer::sum);
					try { Thread.sleep(2); } catch (InterruptedException ex) { return; }
				}
			});
			sampler.setDaemon(true);
			sampler.start();
			for (int i = 0; i < RELOADS; i++) {
				long reloadStarted = System.nanoTime();
				ReloadResult result = app.reload();
				assertThat(result.reloaded()).as("reload failed: %s", result.error()).isTrue();
				System.out.println("[scale] reload " + (i + 1) + ": wall=" + millisSince(reloadStarted) + " ms, generation="
						+ result.durationMillis() + " ms");
			}
			sampling.set(false);
			sampler.join();
			int total = samples.values().stream().mapToInt(Integer::intValue).sum();
			samples.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
				.forEach((entry) -> System.out.println("[sample] " + entry.getValue() + "/" + total + entry.getKey()));
			assertThat(app.getOk("/scale")).isEqualTo("Parent00000");
		}
	}

	private static long millisSince(long nanos) {
		return (System.nanoTime() - nanos) / 1_000_000;
	}

}
