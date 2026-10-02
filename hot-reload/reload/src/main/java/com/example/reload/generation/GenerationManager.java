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
import com.example.reload.layout.ConfigurationPlacementChecker;
import com.example.reload.layout.ReloadLayout;
import com.example.reload.watch.ChangePlanner;
import com.example.reload.watch.ChangePoller;
import com.example.reload.restart.FullRestart;

/**
 * 부모 context의 bean. 자식 세대를 만들고 교체하고 폐기한다.
 */
public class GenerationManager {

	private static final Log logger = LogFactory.getLog(GenerationManager.class);

	private static final String PLACEMENT_RULE = "Configuration placement rule: infrastructure configuration belongs "
			+ "in the parent; only Advisor or BeanPostProcessor based @Enable* annotations belong in the child "
			+ "(set reload.placement-check.enabled=false to disable).";

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

	private volatile ChangePoller watcher;

	private final ChangePlanner changePlanner;
	private ChangePlanner.Snapshot baseline;
	private final FullRestart fullRestart;
	private boolean stopped;
	private final Set<String> knownBusinessClasses = new java.util.HashSet<>();

	private volatile List<String> basePackages;

	private boolean classpathChecked;

	/**
	 * 마지막으로 경고한 자식 설정 배치 위반(문자열).
	 */
	private List<String> childPlacementViolations = List.of();

	public GenerationManager(ReloadProperties properties, ReloadLayout layout, ConfigurableApplicationContext parentContext,
			Consumer<ClassLoader> cacheCleaner) {
		this(properties, layout, parentContext, cacheCleaner, new FullRestart() {
			public boolean available() { return false; }
			public void request() { throw new IllegalStateException("No full restart handler"); }
		});
	}

	public GenerationManager(ReloadProperties properties, ReloadLayout layout, ConfigurableApplicationContext parentContext,
			Consumer<ClassLoader> cacheCleaner, FullRestart fullRestart) {
		Assert.isInstanceOf(WebApplicationContext.class, parentContext, "Parent context must be a web context");
		this.properties = properties;
		this.layout = layout;
		this.parentContext = parentContext;
		this.hostClassLoader = (parentContext.getClassLoader() != null) ? parentContext.getClassLoader()
				: ClassUtils.getDefaultClassLoader();
		this.cacheCleaner = cacheCleaner;
		this.drainScheduler = createDrainScheduler(this.hostClassLoader);
		this.changePlanner = new ChangePlanner(properties, layout);
		this.baseline = this.changePlanner.snapshot();
		this.fullRestart = fullRestart;
		this.knownBusinessClasses.addAll(businessClasses(this.baseline));
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
		if (this.properties.isHybrid() && !reload()) {
			throw new IllegalStateException("Initial reload generation could not start; inspect the preceding error");
		}
	}

	private void startWatching() {
		this.watcher = new ChangePoller(this.changePlanner, this.baseline, this.properties.getPollInterval(),
				this.properties.getQuietPeriod(), this.hostClassLoader, this::onStableChange);
		logger.info("Watching application outputs and reload.watch-paths (mode="
				+ (this.properties.isHybrid() ? "hybrid" : "full-restart") + ")");
	}

	private synchronized void onStableChange(ChangePlanner.Snapshot snapshot) {
		if (this.stopped || this.changePlanner.plan(this.baseline, snapshot).action() == ChangePlanner.Action.NONE) {
			return;
		}
		ReloadResult result = reloadWithResult();
		logger.info("Change action=" + result.action() + ", reasons=" + result.reasons() + ", error=" + result.error());
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

	@FunctionalInterface
	public interface GenerationOperation<T> {
		T invoke(AnnotationConfigWebApplicationContext context) throws Throwable;
	}

	/** HTTP 외의 진입점도 요청 전체에 같은 세대를 고정한다. 중첩 호출은 현재 세대를 재사용한다. */
	public <T> T withGeneration(GenerationOperation<T> operation) throws Throwable {
		AnnotationConfigWebApplicationContext existing = GenerationScope.current();
		if (existing != null && existing.getParent() == this.parentContext) {
			return operation.invoke(existing);
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			Generation generation = this.current.get();
			if (generation == null) { break; }
			if (!generation.acquire()) { continue; }
			Thread thread = Thread.currentThread();
			ClassLoader previous = thread.getContextClassLoader();
			thread.setContextClassLoader(generation.classLoader());
			try (GenerationScope scope = GenerationScope.enter(generation.context())) {
				return operation.invoke(generation.context());
			}
			finally {
				thread.setContextClassLoader(previous);
				generation.release();
			}
		}
		throw new IllegalStateException("No active reload generation");
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
		if (this.stopped) {
			return new ReloadResult(false, previousId, previousId, 0, "Application is stopping");
		}
		ChangePlanner.Snapshot candidate;
		try {
			candidate = this.changePlanner.snapshot();
		}
		catch (RuntimeException ex) {
			return new ReloadResult(false, previousId, previousId, 0, summarize(ex));
		}
		ChangePlanner.Plan plan = this.changePlanner.plan(this.baseline, candidate);
		if (!this.properties.isHybrid() || plan.action() == ChangePlanner.Action.RESTART) {
			if (!this.fullRestart.available()) {
				return new ReloadResult(false, previousId, previousId, 0,
						"Infrastructure changed. Start with RestartLauncher to enable full restart.",
						"restart-required", plan.reasons());
			}
			this.fullRestart.request();
			return new ReloadResult(false, previousId, previousId, 0, null, "restart-requested", plan.reasons());
		}
		return buildGeneration(candidate, plan.reasons());
	}

	/** 변경 정책과 분리된 세대 구성 단계. API/감시는 반드시 reloadWithResult를 통해 진입한다. */
	synchronized ReloadResult buildGeneration(ChangePlanner.Snapshot candidate, List<String> reasons) {
		int previousId = currentGenerationId();
		if (!this.classpathChecked) {
			checkClasspath();
			checkParentPlacement();
			this.classpathChecked = true;
		}
		int id = this.generationIds.incrementAndGet();
		long startTime = System.nanoTime();
		Set<String> present = businessClasses(candidate);
		Set<String> deleted = new java.util.HashSet<>(this.knownBusinessClasses);
		deleted.removeAll(present);
		GenerationClassLoader loader = new GenerationClassLoader(this.hostClassLoader, this.layout.classpathUrls(),
				this.layout.ownership(), deleted);
		Generation generation = new Generation(id, loader, this.cacheCleaner);
		Throwable failure = null;
		String phases = "";
		Thread thread = Thread.currentThread();
		ClassLoader previousTccl = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			GenerationApplicationContext context = createContext(id, loader);
			generation.setContext(context);
			context.refresh();
			long refreshed = System.nanoTime();
			generation.setDispatcher(createDispatcher(id, context));
			this.parentContext.getBeanProvider(GenerationIntegration.class).orderedStream()
					.forEach((integration) -> integration.validate(context));
			long initialized = System.nanoTime();
			boolean unchanged = candidate.equals(this.changePlanner.snapshot());
			phases = " [refresh=" + (refreshed - startTime) / 1_000_000 + " ms " + context.phases() + ", dispatcher="
					+ (initialized - refreshed) / 1_000_000 + " ms, verify="
					+ (System.nanoTime() - initialized) / 1_000_000 + " ms]";
			if (!unchanged) {
				throw new IllegalStateException("Files changed while building the generation; retry after compilation finishes");
			}
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
		checkChildPlacement(id, generation.context(), loader);
		Generation previous = this.current.getAndSet(generation);
		this.baseline = candidate;
		this.knownBusinessClasses.addAll(present);
		logger.info("Generation " + id + " started in " + durationMillis + " ms"
				+ ((previous != null) ? " (replacing generation " + previous.id() + ")" : "") + phases);
		if (previous != null) {
			previous.retire(this.drainScheduler, this.properties.getDrainTimeout());
			if (!previous.isDisposed()) {
				this.draining.add(previous);
			}
		}
		this.draining.removeIf(Generation::isDisposed);
		return new ReloadResult(true, id, previousId, durationMillis, null, "reload", reasons);
	}

	private Set<String> businessClasses(ChangePlanner.Snapshot snapshot) {
		return snapshot.files().values().stream().filter((entry) -> entry.className() != null)
				.map(ChangePlanner.Entry::className).filter((name) -> !this.layout.ownership().isParentOwned(name))
				.collect(java.util.stream.Collectors.toSet());
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

	public String strategy() { return this.properties.isHybrid() ? "hybrid" : "full-restart"; }

	public boolean restartAvailable() { return this.fullRestart.available(); }

	private GenerationApplicationContext createContext(int id, ClassLoader loader) {
		GenerationApplicationContext context = new GenerationApplicationContext(this.layout);
		context.setId(this.parentContext.getId() + ":generation-" + id);
		context.setDisplayName("Reload generation " + id);
		context.setParent(this.parentContext);
		context.setClassLoader(loader);
		context.setServletContext(servletContext());
		context.register(ChildInfrastructureConfiguration.class, ChildWebMvcConfig.class);
		this.parentContext.getBeanProvider(GenerationIntegration.class).orderedStream()
				.forEach((integration) -> integration.configure(context));
		List<String> basePackages = basePackages();
		if (!basePackages.isEmpty()) {
			context.scan(basePackages.toArray(String[]::new));
		}
		return context;
	}

	/**
	 * 자식이 스캔할 패키지. 설정이 없으면 부모의 auto-configuration 패키지({@code @SpringBootApplication}
	 * 클래스의 패키지)를 쓴다.
	 * <p>
	 * 자식 컴포넌트는 business-packages 안에만 있으므로 스캔 범위를 그 안으로 좁힌다. 좁히지 않으면 부모 소유
	 * 클래스 파일까지 세대마다 읽고 나서 필터로 버린다.
	 */
	private List<String> basePackages() {
		List<String> basePackages = this.basePackages;
		if (basePackages == null) {
			basePackages = this.properties.getBasePackages();
			if (basePackages.isEmpty() && AutoConfigurationPackages.has(this.parentContext.getBeanFactory())) {
				basePackages = AutoConfigurationPackages.get(this.parentContext.getBeanFactory());
				logger.info("reload.base-packages not set; scanning " + basePackages + " in each generation");
			}
			this.basePackages = narrowToBusinessPackages(basePackages, this.properties.getBusinessPackages());
		}
		return this.basePackages;
	}

	static List<String> narrowToBusinessPackages(List<String> basePackages, List<String> businessPackages) {
		if (businessPackages.isEmpty()) {
			return List.copyOf(basePackages);
		}
		Set<String> narrowed = new java.util.LinkedHashSet<>();
		for (String basePackage : basePackages) {
			for (String businessPackage : businessPackages) {
				if (isWithin(businessPackage, basePackage)) {
					narrowed.add(businessPackage);
				}
				else if (isWithin(basePackage, businessPackage)) {
					narrowed.add(basePackage);
				}
			}
		}
		return List.copyOf(narrowed);
	}

	private static boolean isWithin(String packageName, String enclosing) {
		return packageName.equals(enclosing) || packageName.startsWith(enclosing + ".");
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

	/**
	 * 부모에 선언되어 자식 bean에는 적용되지 않는 기능({@code @EnableAsync}, Advisor bean 등)을 경고한다.
	 */
	private void checkParentPlacement() {
		if (!this.properties.getPlacementCheck().isEnabled()) {
			return;
		}
		try {
			List<String> findings = ConfigurationPlacementChecker
				.findParentOnlyFeatures(this.parentContext.getBeanFactory());
			if (!findings.isEmpty()) {
				logger.warn(PLACEMENT_RULE + " Parent configuration that does not apply to generation beans:\n  "
						+ String.join("\n  ", findings));
			}
		}
		catch (RuntimeException | LinkageError ex) {
			logger.debug("Configuration placement check of the parent failed", ex);
		}
	}

	/**
	 * 자식 세대의 설정 배치 규칙 위반을 경고한다. 같은 위반을 세대마다 반복하지 않도록 목록이 바뀔 때만 남긴다.
	 * 위반은 문자열로만 보관한다(자식 클래스를 붙잡지 않도록).
	 */
	private void checkChildPlacement(int id, AnnotationConfigWebApplicationContext context, ClassLoader loader) {
		ReloadProperties.PlacementCheck placementCheck = this.properties.getPlacementCheck();
		if (!placementCheck.isEnabled()) {
			return;
		}
		List<String> violations;
		try {
			violations = ConfigurationPlacementChecker.findChildViolations(context.getBeanFactory(),
					(type) -> type.getClassLoader() == loader, placementCheck.getAllowedChildAnnotations());
		}
		catch (RuntimeException | LinkageError ex) {
			logger.debug("Configuration placement check of generation " + id + " failed", ex);
			return;
		}
		if (violations.equals(this.childPlacementViolations)) {
			return;
		}
		if (violations.isEmpty()) {
			logger.info("Configuration placement violations resolved in generation " + id);
		}
		else {
			logger.warn(PLACEMENT_RULE + " Violations in generation " + id + ":\n  " + String.join("\n  ", violations));
		}
		this.childPlacementViolations = violations;
	}

	@PreDestroy
	public synchronized void shutdown() {
		this.stopped = true;
		ChangePoller watcher = this.watcher;
		if (watcher != null) {
			watcher.close();
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
