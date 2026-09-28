package com.example.reload.generation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import com.example.reload.ReloadProperties;
import com.example.reload.child.ChildInfrastructureConfiguration;
import com.example.reload.child.ChildWebMvcConfig;
import com.example.reload.layout.BoundaryChecker;
import com.example.reload.layout.ReloadLayout;
import com.example.reload.watch.ClassPathChangeWatcher;

/**
 * 부모 context의 bean. 자식 세대를 만들고 교체하고 폐기한다.
 */
public class GenerationManager {

	private static final Log logger = LogFactory.getLog(GenerationManager.class);

	private final ReloadProperties properties;

	private final ReloadLayout layout;

	private final ConfigurableApplicationContext parentContext;

	private final ClassLoader hostClassLoader;

	private final Consumer<ClassLoader> cacheCleaner;

	private final AtomicReference<Generation> current = new AtomicReference<>();

	private final AtomicInteger generationIds = new AtomicInteger();

	private final AtomicInteger failedReloads = new AtomicInteger();

	private final ScheduledExecutorService drainScheduler;

	/**
	 * retire됐지만 진행 중 요청이 남아 아직 dispose되지 않은 세대.
	 */
	private final Set<Generation> draining = ConcurrentHashMap.newKeySet();

	private volatile ClassPathChangeWatcher watcher;

	private volatile List<String> basePackages;

	private boolean classpathChecked;

	public GenerationManager(ReloadProperties properties, ReloadLayout layout, ConfigurableApplicationContext parentContext,
			Consumer<ClassLoader> cacheCleaner) {
		Assert.isInstanceOf(WebApplicationContext.class, parentContext, "Parent context must be a web context");
		this.properties = properties;
		this.layout = layout;
		this.parentContext = parentContext;
		this.hostClassLoader = (parentContext.getClassLoader() != null) ? parentContext.getClassLoader()
				: ClassUtils.getDefaultClassLoader();
		this.cacheCleaner = cacheCleaner;
		this.drainScheduler = createDrainScheduler(this.hostClassLoader);
	}

	private static ScheduledExecutorService createDrainScheduler(ClassLoader hostClassLoader) {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, (runnable) -> {
			Thread thread = new Thread(runnable, "reload-drain");
			thread.setDaemon(true);
			thread.setContextClassLoader(hostClassLoader);
			return thread;
		});
		// 정상 drain으로 취소된 강제 dispose 작업이 큐에 남아 Generation을 붙잡지 않도록 한다.
		executor.setRemoveOnCancelPolicy(true);
		return Executors.unconfigurableScheduledExecutorService(executor);
	}

	/**
	 * 부모가 준비되면 감시를 시작하고 첫 세대를 로드한다. 감시를 먼저 시작해서 첫 로드와 감시
	 * 시작 사이의 변경을 놓치지 않는다.
	 */
	@EventListener
	public void onApplicationReady(ApplicationReadyEvent event) {
		if (event.getApplicationContext() != this.parentContext) {
			return;
		}
		ReloadProperties.Mode mode = this.properties.getTrigger().getMode();
		if (mode.isWatch()) {
			startWatching();
		}
		if (mode.isApi()) {
			logger.info("Reload API available: POST " + this.properties.getTrigger().getApiPath());
		}
		reload();
	}

	private void startWatching() {
		List<Path> directories = this.layout.watchedDirectories();
		if (directories.isEmpty()) {
			logger.warn("No reload.classpath directories to watch; automatic reload is disabled");
			return;
		}
		ClassPathChangeWatcher watcher = new ClassPathChangeWatcher(directories, this.properties.getPollInterval(),
				this.properties.getQuietPeriod(), this.properties.getExcludePatterns(), this.hostClassLoader,
				this.layout.ownership(), this::reload);
		watcher.start();
		this.watcher = watcher;
		logger.info("Watching " + directories + " for changes");
	}

	/**
	 * 현재 세대. 요청 처리 쪽은 반드시 {@link Generation#acquire()}로 등록한 뒤 사용해야 한다.
	 */
	Generation current() {
		return this.current.get();
	}

	/**
	 * 현재 세대 번호. 아직 로드된 세대가 없으면 0.
	 */
	public int currentGenerationId() {
		Generation generation = this.current.get();
		return (generation != null) ? generation.id() : 0;
	}

	/**
	 * 지금까지 실패한 재로딩 횟수.
	 */
	public int failedReloads() {
		return this.failedReloads.get();
	}

	/**
	 * 새 세대를 만들어 현재 세대와 교체한다. 실패하면 기존 세대를 그대로 둔다.
	 * @return 교체에 성공하면 {@code true}
	 * @see #reloadWithResult()
	 */
	public boolean reload() {
		return reloadWithResult().reloaded();
	}

	/**
	 * 새 세대를 만들어 현재 세대와 교체하고 결과를 돌려준다. 실패하면 기존 세대를 그대로 둔다. 파일 감시,
	 * 재로딩 API, 애플리케이션 코드 어디서 호출해도 되며 동시에 하나만 실행된다.
	 */
	public synchronized ReloadResult reloadWithResult() {
		int previousId = currentGenerationId();
		if (!this.classpathChecked) {
			checkClasspath();
			this.classpathChecked = true;
		}
		int id = this.generationIds.incrementAndGet();
		long startTime = System.nanoTime();
		GenerationClassLoader loader = new GenerationClassLoader(this.hostClassLoader, this.layout.classpathUrls(),
				this.layout.ownership());
		Generation generation = new Generation(id, loader, this.cacheCleaner);
		Throwable failure = null;
		Thread thread = Thread.currentThread();
		ClassLoader previousTccl = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			AnnotationConfigWebApplicationContext context = createContext(id, loader);
			generation.setContext(context);
			context.refresh();
			generation.setDispatcher(createDispatcher(id, context));
		}
		catch (Exception | LinkageError ex) {
			failure = ex;
		}
		finally {
			thread.setContextClassLoader(previousTccl);
		}
		long durationMillis = (System.nanoTime() - startTime) / 1_000_000;
		if (failure != null) {
			this.failedReloads.incrementAndGet();
			logger.error("Failed to start generation " + id + "; keeping " + this.current.get(), failure);
			generation.dispose();
			return new ReloadResult(false, previousId, previousId, durationMillis, summarize(failure));
		}
		Generation previous = this.current.getAndSet(generation);
		logger.info("Generation " + id + " started in " + durationMillis + " ms"
				+ ((previous != null) ? " (replacing generation " + previous.id() + ")" : ""));
		if (previous != null) {
			previous.retire(this.drainScheduler, this.properties.getDrainTimeout());
			if (!previous.isDisposed()) {
				this.draining.add(previous);
			}
		}
		this.draining.removeIf(Generation::isDisposed);
		return new ReloadResult(true, id, previousId, durationMillis, null);
	}

	/**
	 * 가장 안쪽 원인의 클래스 이름과 메시지. 예외 객체 자체는 자식 클래스를 붙잡으므로 문자열만 남긴다.
	 */
	private static String summarize(Throwable failure) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
		return cause.getClass().getName() + ((cause.getMessage() != null) ? ": " + cause.getMessage() : "");
	}

	/**
	 * 현재 트리거 방식.
	 */
	public ReloadProperties.Mode triggerMode() {
		return this.properties.getTrigger().getMode();
	}

	private AnnotationConfigWebApplicationContext createContext(int id, ClassLoader loader) {
		AnnotationConfigWebApplicationContext context = new GenerationApplicationContext(this.layout.ownership());
		context.setId(this.parentContext.getId() + ":generation-" + id);
		context.setDisplayName("Reload generation " + id);
		context.setParent(this.parentContext);
		context.setClassLoader(loader);
		context.setServletContext(servletContext());
		context.register(ChildInfrastructureConfiguration.class, ChildWebMvcConfig.class);
		List<String> basePackages = basePackages();
		if (!basePackages.isEmpty()) {
			context.scan(basePackages.toArray(String[]::new));
		}
		return context;
	}

	/**
	 * 자식이 스캔할 패키지. 설정이 없으면 부모의 auto-configuration 패키지({@code @SpringBootApplication}
	 * 클래스의 패키지)를 쓴다.
	 */
	private List<String> basePackages() {
		List<String> basePackages = this.basePackages;
		if (basePackages == null) {
			basePackages = this.properties.getBasePackages();
			if (basePackages.isEmpty() && AutoConfigurationPackages.has(this.parentContext.getBeanFactory())) {
				basePackages = AutoConfigurationPackages.get(this.parentContext.getBeanFactory());
				logger.info("reload.base-packages not set; scanning " + basePackages + " in each generation");
			}
			this.basePackages = List.copyOf(basePackages);
		}
		return this.basePackages;
	}

	private DispatcherServlet createDispatcher(int id, WebApplicationContext context) throws Exception {
		DispatcherServlet dispatcher = new DispatcherServlet(context);
		// 세대마다 ServletContext attribute가 쌓여 이전 context를 붙잡는 것을 막는다.
		dispatcher.setPublishContext(false);
		dispatcher.init(new GenerationServletConfig("dispatcher-gen-" + id, servletContext()));
		return dispatcher;
	}

	private ServletContext servletContext() {
		ServletContext servletContext = ((WebApplicationContext) this.parentContext).getServletContext();
		Assert.state(servletContext != null, "Parent context has no ServletContext");
		return servletContext;
	}

	private void checkClasspath() {
		for (Path path : this.layout.classpath()) {
			if (!Files.exists(path)) {
				logger.warn("reload.classpath entry does not exist (yet): " + path);
			}
		}
		Map<String, Set<String>> violations = BoundaryChecker.findViolations(this.layout.watchedDirectories(),
				this.layout.ownership());
		if (!violations.isEmpty()) {
			StringBuilder message = new StringBuilder("Parent-owned classes reference reloadable (child-owned) "
					+ "classes. The parent class loader will load its own copies of them, which breaks injection "
					+ "and casts between parent and child and never reloads. Move the referenced types to "
					+ "reload.parent-packages " + this.layout.ownership().parentPackages()
					+ " or remove the references:");
			violations.forEach((parentClass, childClasses) -> message.append("\n  ")
				.append(parentClass)
				.append(" -> ")
				.append(childClasses));
			logger.warn(message);
		}
	}

	@PreDestroy
	public void shutdown() {
		ClassPathChangeWatcher watcher = this.watcher;
		if (watcher != null) {
			watcher.stop();
			this.watcher = null;
		}
		Generation generation = this.current.getAndSet(null);
		if (generation != null) {
			generation.dispose();
		}
		// drain 중인 이전 세대가 있으면 기다리지 않고 바로 dispose한다.
		this.drainScheduler.shutdownNow();
		this.draining.forEach(Generation::dispose);
		this.draining.clear();
	}

	/**
	 * 세대별 {@link DispatcherServlet}에 넘기는 합성 {@link ServletConfig}.
	 */
	private record GenerationServletConfig(String servletName, ServletContext servletContext)
			implements ServletConfig {

		@Override
		public String getServletName() {
			return this.servletName;
		}

		@Override
		public ServletContext getServletContext() {
			return this.servletContext;
		}

		@Override
		public String getInitParameter(String name) {
			return null;
		}

		@Override
		public Enumeration<String> getInitParameterNames() {
			return Collections.emptyEnumeration();
		}

	}

}
