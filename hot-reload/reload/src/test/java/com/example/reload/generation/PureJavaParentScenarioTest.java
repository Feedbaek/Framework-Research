package com.example.reload.generation;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.example.reload.fixture.KnownIssue;

import static com.example.reload.generation.ConsumerApp.sources;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모 소유 패키지({@code reload.parent-packages})에 Spring 애너테이션이 없는 순수 자바 코드(도메인 모델, 값 객체,
 * 계산 로직, 유틸리티)만 두는 배치. Spring 코드(컨트롤러, 서비스, 설정)는 모두 자식이다.
 * <p>
 * 실제 소비자 앱처럼 부모와 자식이 같은 출력 디렉터리를 보도록 {@link ConsumerApp}으로 띄운다. 부모 패키지는
 * {@value ConsumerApp#PARENT_PACKAGE}, 자식 Spring 코드는 {@code com.example.app.web}이다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PureJavaParentScenarioTest {

	// ---- 부모 소유 순수 자바 코드 (com.example.app.domain) ----

	private static final String MONEY = """
			package com.example.app.domain;

			public record Money(long amount) {

				public Money plus(Money other) {
					return new Money(this.amount + other.amount);
				}

			}
			""";

	private static final String ORDER_STATUS = """
			package com.example.app.domain;

			public enum OrderStatus {
				CREATED, PAID
			}
			""";

	private static final String PRICE_CALCULATOR_TEMPLATE = """
			package com.example.app.domain;

			public final class PriceCalculator {

				private static final int TAX_PERCENT = %d;

				private PriceCalculator() {
				}

				public static Money withTax(Money price) {
					return new Money(price.amount() * (100 + TAX_PERCENT) / 100);
				}

			}
			""";

	private static final String ORDER = """
			package com.example.app.domain;

			public record Order(long id, Money total, OrderStatus status) {
			}
			""";

	private static final String ORDER_STORE = """
			package com.example.app.domain;

			import java.util.Map;
			import java.util.concurrent.ConcurrentHashMap;

			/** 메모리 저장소. 부모 타입(Order)만 담는다. */
			public final class OrderStore {

				private static final Map<Long, Order> ORDERS = new ConcurrentHashMap<>();

				private OrderStore() {
				}

				public static void save(Order order) {
					ORDERS.put(order.id(), order);
				}

				public static Order find(long id) {
					return ORDERS.get(id);
				}

			}
			""";

	private static final String DISCOUNT_POLICY = """
			package com.example.app.domain;

			public interface DiscountPolicy {

				Money apply(Money price);

			}
			""";

	private static final String ABSTRACT_GREETER = """
			package com.example.app.domain;

			public abstract class AbstractGreeter {

				public final String greet(String name) {
					return prefix() + name;
				}

				protected abstract String prefix();

			}
			""";

	private static final String DOMAIN_EXCEPTION = """
			package com.example.app.domain;

			public class DomainException extends RuntimeException {

				public DomainException(String message) {
					super(message);
				}

			}
			""";

	private static final String CREATE_ORDER_REQUEST = """
			package com.example.app.domain;

			import jakarta.validation.constraints.NotBlank;
			import jakarta.validation.constraints.Positive;

			public record CreateOrderRequest(@NotBlank String item, @Positive long amount) {
			}
			""";

	private static final String NOTIFIER = """
			package com.example.app.domain;

			import java.util.List;
			import java.util.concurrent.CopyOnWriteArrayList;
			import java.util.function.Consumer;

			/** 정적 리스너 레지스트리(옵저버 패턴). */
			public final class Notifier {

				private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

				private Notifier() {
				}

				public static void register(Consumer<String> listener) {
					LISTENERS.add(listener);
				}

				public static int fire(String message) {
					LISTENERS.forEach((listener) -> listener.accept(message));
					return LISTENERS.size();
				}

			}
			""";

	private static final String SIMPLE_CACHE = """
			package com.example.app.domain;

			import java.util.Map;
			import java.util.concurrent.ConcurrentHashMap;

			/** 정적 캐시. 값 타입을 가리지 않는다. */
			public final class SimpleCache {

				private static final Map<String, Object> VALUES = new ConcurrentHashMap<>();

				private SimpleCache() {
				}

				public static void put(String key, Object value) {
					VALUES.put(key, value);
				}

				public static Object get(String key) {
					return VALUES.get(key);
				}

			}
			""";

	private static final String REFLECTION_MAPPER = """
			package com.example.app.domain;

			import java.lang.reflect.RecordComponent;
			import java.util.Arrays;
			import java.util.LinkedHashMap;
			import java.util.List;
			import java.util.Map;
			import java.util.concurrent.ConcurrentHashMap;

			/** 리플렉션 결과를 클래스별로 캐시하는 매퍼(흔한 유틸리티 형태). */
			public final class ReflectionMapper {

				private static final Map<Class<?>, List<RecordComponent>> COMPONENTS = new ConcurrentHashMap<>();

				private ReflectionMapper() {
				}

				public static Map<String, Object> toMap(Record record) throws ReflectiveOperationException {
					Map<String, Object> values = new LinkedHashMap<>();
					for (RecordComponent component : COMPONENTS.computeIfAbsent(record.getClass(),
							(type) -> Arrays.asList(type.getRecordComponents()))) {
						values.put(component.getName(), component.getAccessor().invoke(record));
					}
					return values;
				}

			}
			""";

	private static final String REQUEST_CONTEXT = """
			package com.example.app.domain;

			/** 요청 문맥을 담는 ThreadLocal. 호출하는 쪽이 비우는 것을 잊기 쉽다. */
			public final class RequestContext {

				private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

				private RequestContext() {
				}

				public static void set(Object value) {
					CURRENT.set(value);
				}

				public static Object get() {
					return CURRENT.get();
				}

			}
			""";

	private static final String DOMAIN_EXECUTOR = """
			package com.example.app.domain;

			import java.util.concurrent.Callable;
			import java.util.concurrent.ExecutorService;
			import java.util.concurrent.Executors;
			import java.util.concurrent.Future;

			/** 정적 스레드 풀. */
			public final class DomainExecutor {

				private static final ExecutorService POOL = Executors.newFixedThreadPool(2, (runnable) -> {
					Thread thread = new Thread(runnable, "domain-worker");
					thread.setDaemon(true);
					return thread;
				});

				private DomainExecutor() {
				}

				public static <T> Future<T> submit(Callable<T> task) {
					return POOL.submit(task);
				}

			}
			""";

	private static final String PLUGIN_FACTORY = """
			package com.example.app.domain;

			/** 클래스 이름으로 구현체를 만드는 팩토리. */
			public final class PluginFactory {

				private PluginFactory() {
				}

				public static Object create(String className) throws ReflectiveOperationException {
					return Class.forName(className).getDeclaredConstructor().newInstance();
				}

			}
			""";

	private static final String ASYNC_CLASS_RESOLVER = """
			package com.example.app.domain;

			import java.util.concurrent.CompletableFuture;

			/** 공용 ForkJoinPool에서 TCCL로 클래스를 찾는다(병렬 스트림, supplyAsync 안의 리플렉션). */
			public final class AsyncClassResolver {

				private AsyncClassResolver() {
				}

				public static ClassLoader loaderOf(String className) {
					return CompletableFuture.supplyAsync(() -> {
						try {
							return Class.forName(className, false, Thread.currentThread().getContextClassLoader())
								.getClassLoader();
						}
						catch (ClassNotFoundException ex) {
							return null;
						}
					}).join();
				}

			}
			""";

	// ---- 자식 소유 Spring 코드 (com.example.app.web) ----

	private static final String PRICE_CONTROLLER = """
			package com.example.app.web;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RequestParam;
			import org.springframework.web.bind.annotation.RestController;

			import com.example.app.domain.Money;
			import com.example.app.domain.OrderStatus;
			import com.example.app.domain.PriceCalculator;

			@RestController
			public class PriceController {

				@GetMapping("/price")
				public String price(@RequestParam("amount") long amount) {
					return PriceCalculator.withTax(new Money(amount)).amount() + ":" + OrderStatus.PAID;
				}

			}
			""";

	private static final String FREE_SHIPPING = """
			package com.example.app.web;

			import com.example.app.domain.DiscountPolicy;
			import com.example.app.domain.Money;

			public class FreeShipping implements DiscountPolicy {

				@Override
				public Money apply(Money price) {
					return price;
				}

			}
			""";

	@Test
	void childCodeUsesParentDomainLogicAcrossReloads() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, ORDER_STATUS, PRICE_CALCULATOR_TEMPLATE.formatted(10),
				PRICE_CONTROLLER))) {
			assertThat(app.getOk("/price?amount=100")).isEqualTo("110:PAID");

			app.reloadSuccessfully();

			assertThat(app.getOk("/price?amount=100")).isEqualTo("110:PAID");
			assertThat(app.parentClass("com.example.app.domain.Money").getClassLoader())
				.as("parent-owned class is loaded once by the parent").isNotInstanceOf(GenerationClassLoader.class);
		}
	}

	/**
	 * 부모 패키지에 둔 코드는 개발 중 가장 자주 고치는 도메인 로직이기도 하다.
	 */
	@Test
	@KnownIssue("부모 소유 클래스는 부모 클래스로더가 한 번만 로드하므로 재로딩으로 바뀌지 않는다. 재시작해야 "
			+ "반영된다(설계상 제약)")
	void domainLogicChangeIsAppliedAfterReload() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, ORDER_STATUS, PRICE_CALCULATOR_TEMPLATE.formatted(10),
				PRICE_CONTROLLER))) {
			assertThat(app.getOk("/price?amount=100")).isEqualTo("110:PAID");

			app.update(sources(PRICE_CALCULATOR_TEMPLATE.formatted(20)));
			app.reloadSuccessfully();

			assertThat(app.getOk("/price?amount=100")).isEqualTo("120:PAID");
		}
	}

	/**
	 * 부모 클래스를 고치면 빌드 도구가 그 클래스를 쓰는 자식 클래스도 다시 컴파일하므로(이 하네스는 전체를 다시
	 * 쓴다) 자식 세대는 교체된다. 그래도 부모 로직은 옛 버전이므로 재시작 경고가 나와야 한다.
	 */
	/**
	 * 부모 클래스로더는 클래스를 처음 쓰일 때 로드한다. 변경 전에 이미 쓰인 부모 클래스는 옛 버전으로 남고, 아직
	 * 쓰이지 않은 부모 클래스는 변경 뒤의 새 파일에서 로드되어 두 버전이 섞인다.
	 */
	@Test
	@KnownIssue("변경 전에 로드된 부모 클래스(옛 버전)가 변경 뒤 처음 로드된 부모 클래스(새 버전)를 호출해 "
			+ "NoSuchMethodError가 난다. 재시작 전까지 부모 코드가 옛 버전과 새 버전이 섞인 상태가 된다")
	void parentClassesStayConsistentAfterParentOwnedChange() throws Exception {
		String shippingRuleV1 = """
				package com.example.app.domain;

				public final class ShippingRule {

					private ShippingRule() {
					}

					public static Money fee(Money price) {
						return new Money(3000);
					}

				}
				""";
		String shippingRuleV2 = """
				package com.example.app.domain;

				public final class ShippingRule {

					private ShippingRule() {
					}

					public static Money fee(Money price, String region) {
						return new Money("KR".equals(region) ? 2500 : 5000);
					}

				}
				""";
		String checkout = """
				package com.example.app.domain;

				public final class Checkout {

					private Checkout() {
					}

					public static Money total(Money price) {
						return price;
					}

					public static Money totalWithShipping(Money price) {
						return price.plus(%s);
					}

				}
				""";
		String controller = """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RequestParam;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.Checkout;
				import com.example.app.domain.Money;

				@RestController
				public class CheckoutController {

					@GetMapping("/total")
					public long total(@RequestParam("amount") long amount) {
						return Checkout.total(new Money(amount)).amount();
					}

					@GetMapping("/total-with-shipping")
					public long totalWithShipping(@RequestParam("amount") long amount) {
						return Checkout.totalWithShipping(new Money(amount)).amount();
					}

				}
				""";
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, shippingRuleV1,
				checkout.formatted("ShippingRule.fee(price)"), controller))) {
			// Checkout은 로드되지만 ShippingRule은 아직 쓰이지 않았다.
			assertThat(app.getOk("/total?amount=10000")).isEqualTo("10000");

			app.update(sources(shippingRuleV2, checkout.formatted("ShippingRule.fee(price, \"KR\")")));
			app.reloadSuccessfully();

			assertThat(app.getOk("/total-with-shipping?amount=10000")).isEqualTo("12500");
		}
	}

	@Test
	void watchModeWarnsThatParentOwnedChangesNeedRestart(CapturedOutput output) throws Exception {
		try (ConsumerApp app = ConsumerApp.start("watch", sources(MONEY, ORDER_STATUS,
				PRICE_CALCULATOR_TEMPLATE.formatted(10), PRICE_CONTROLLER))) {
			// 부모 클래스는 처음 쓰일 때 로드되므로 변경 전에 한 번 써서 로드해 둔다.
			assertThat(app.getOk("/price?amount=100")).isEqualTo("110:PAID");

			app.update(sources(PRICE_CALCULATOR_TEMPLATE.formatted(20)));

			// 하네스는 출력 디렉터리를 통째로 다시 쓰므로 목록에는 다른 부모 소유 클래스도 함께 나온다.
			AbstractReloadScenarioTest.await("restart warning", () -> output.getOut()
				.lines()
				.anyMatch((line) -> line.contains("Change action=restart-required")
						&& line.contains("PriceCalculator.class")));
			assertThat(app.getOk("/price?amount=100")).as("parent logic is still the old version")
				.isEqualTo("110:PAID");
		}
	}

	/**
	 * 빌드 후 재로딩 API를 호출하는 방식({@code api} 모드, 샘플의 기본값)에서도 부모 소유 클래스가 바뀌었으면
	 * 재시작이 필요하다는 것을 알려야 한다. 그렇지 않으면 재로딩은 성공했는데 옛 로직이 도는 상태가 된다.
	 */
	@Test
	void reloadApiWarnsThatParentOwnedChangesNeedRestart(CapturedOutput output) throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, ORDER_STATUS, PRICE_CALCULATOR_TEMPLATE.formatted(10),
				PRICE_CONTROLLER))) {
			app.update(sources(PRICE_CALCULATOR_TEMPLATE.formatted(20)));

			ReloadResult result = app.reload();

			assertThat(result.action()).isEqualTo("restart-required");
			assertThat(result.reloaded()).as("reload reported as applied although parent code is stale").isFalse();
		}
	}

	/**
	 * 문서의 권장 패턴: 세대를 넘어 보관하는 값은 부모 타입으로 만든다. 재로딩 뒤에도 캐스팅 문제가 없다.
	 */
	@Test
	void parentTypesKeptInParentStaticStateSurviveReload() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, ORDER_STATUS, ORDER, ORDER_STORE, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.PathVariable;
				import org.springframework.web.bind.annotation.RequestParam;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.Money;
				import com.example.app.domain.Order;
				import com.example.app.domain.OrderStatus;
				import com.example.app.domain.OrderStore;

				@RestController
				public class OrderController {

					@GetMapping("/orders/create")
					public String create(@RequestParam("id") long id, @RequestParam("amount") long amount) {
						OrderStore.save(new Order(id, new Money(amount), OrderStatus.CREATED));
						return "created";
					}

					@GetMapping("/orders/{id}")
					public String find(@PathVariable("id") long id) {
						Order order = OrderStore.find(id);
						return order.total().amount() + ":" + order.status();
					}

				}
				"""))) {
			app.getOk("/orders/create?id=1&amount=100");

			app.reloadSuccessfully();

			assertThat(app.getOk("/orders/1")).isEqualTo("100:CREATED");
			long[] ids = { 100 };
			app.assertPreviousGenerationsCollected(() -> app.getOk("/orders/create?id=" + (ids[0]++) + "&amount=1"));
		}
	}

	@Test
	void childImplementationOfParentInterfaceIsReloaded() throws Exception {
		String discount = """
				package com.example.app.web;

				import org.springframework.stereotype.Component;

				import com.example.app.domain.DiscountPolicy;
				import com.example.app.domain.Money;

				@Component
				public class PercentDiscount implements DiscountPolicy {

					@Override
					public Money apply(Money price) {
						return new Money(price.amount() * (100 - %d) / 100);
					}

				}
				""";
		String controller = """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RequestParam;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.DiscountPolicy;
				import com.example.app.domain.Money;

				@RestController
				public class DiscountController {

					private final DiscountPolicy policy;

					public DiscountController(DiscountPolicy policy) {
						this.policy = policy;
					}

					@GetMapping("/discount")
					public long discount(@RequestParam("amount") long amount) {
						return this.policy.apply(new Money(amount)).amount();
					}

				}
				""";
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, DISCOUNT_POLICY, discount.formatted(10), controller))) {
			assertThat(app.getOk("/discount?amount=100")).isEqualTo("90");

			app.update(sources(discount.formatted(20)));
			app.reloadSuccessfully();

			assertThat(app.getOk("/discount?amount=100")).isEqualTo("80");
		}
	}

	@Test
	void childSubclassOfParentAbstractClassIsReloaded() throws Exception {
		String greeter = """
				package com.example.app.web;

				import org.springframework.stereotype.Component;

				import com.example.app.domain.AbstractGreeter;

				@Component
				public class FriendlyGreeter extends AbstractGreeter {

					@Override
					protected String prefix() {
						return "%s";
					}

				}
				""";
		String controller = """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class GreetController {

					private final FriendlyGreeter greeter;

					public GreetController(FriendlyGreeter greeter) {
						this.greeter = greeter;
					}

					@GetMapping("/greet")
					public String greet() {
						return this.greeter.greet("kim");
					}

				}
				""";
		try (ConsumerApp app = ConsumerApp.start(sources(ABSTRACT_GREETER, greeter.formatted("Hello, "), controller))) {
			assertThat(app.getOk("/greet")).isEqualTo("Hello, kim");

			app.update(sources(greeter.formatted("Hi, ")));
			app.reloadSuccessfully();

			assertThat(app.getOk("/greet")).isEqualTo("Hi, kim");
		}
	}

	@Test
	void parentDomainExceptionIsHandledByChildAdvice() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(DOMAIN_EXCEPTION, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.DomainException;

				@RestController
				public class StockController {

					@GetMapping("/stock")
					public String stock() {
						throw new DomainException("out of stock");
					}

				}
				""", """
				package com.example.app.web;

				import org.springframework.http.HttpStatus;
				import org.springframework.web.bind.annotation.ExceptionHandler;
				import org.springframework.web.bind.annotation.ResponseStatus;
				import org.springframework.web.bind.annotation.RestControllerAdvice;

				import com.example.app.domain.DomainException;

				@RestControllerAdvice
				public class DomainExceptionAdvice {

					@ExceptionHandler(DomainException.class)
					@ResponseStatus(HttpStatus.CONFLICT)
					public String handle(DomainException ex) {
						return ex.getMessage();
					}

				}
				"""))) {
			HttpResponse<String> response = app.get("/stock");

			assertThat(response.statusCode()).isEqualTo(409);
			assertThat(response.body()).isEqualTo("out of stock");
		}
	}

	@Test
	void parentRecordsWorkAsValidatedRequestAndResponseBodies() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, ORDER_STATUS, ORDER, CREATE_ORDER_REQUEST, """
				package com.example.app.web;

				import jakarta.validation.Valid;

				import org.springframework.web.bind.annotation.PostMapping;
				import org.springframework.web.bind.annotation.RequestBody;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.CreateOrderRequest;
				import com.example.app.domain.Money;
				import com.example.app.domain.Order;
				import com.example.app.domain.OrderStatus;

				@RestController
				public class CreateOrderController {

					@PostMapping("/orders")
					public Order create(@Valid @RequestBody CreateOrderRequest request) {
						return new Order(1, new Money(request.amount()), OrderStatus.CREATED);
					}

				}
				"""))) {
			HttpResponse<String> invalid = app.postJson("/orders", "{\"item\":\"\",\"amount\":0}");
			app.reloadSuccessfully();
			HttpResponse<String> valid = app.postJson("/orders", "{\"item\":\"book\",\"amount\":100}");

			assertThat(invalid.statusCode()).isEqualTo(400);
			assertThat(valid.statusCode()).isEqualTo(200);
			assertThat(valid.body()).isEqualTo("{\"id\":1,\"total\":{\"amount\":100},\"status\":\"CREATED\"}");
		}
	}

	/**
	 * 문서 3장의 대응책: 세션에 넣는 타입을 부모 타입으로 만들면 재로딩 뒤에도 쓸 수 있다.
	 */
	@Test
	void sessionAttributeOfParentTypeSurvivesReload() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, ORDER_STATUS, ORDER, """
				package com.example.app.web;

				import jakarta.servlet.http.HttpSession;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.Money;
				import com.example.app.domain.Order;
				import com.example.app.domain.OrderStatus;

				@RestController
				public class CartController {

					@GetMapping("/cart/put")
					public String put(HttpSession session) {
						session.setAttribute("order", new Order(7, new Money(300), OrderStatus.CREATED));
						return "ok";
					}

					@GetMapping("/cart")
					public String get(HttpSession session) {
						Order order = (Order) session.getAttribute("order");
						return order.id() + ":" + order.total().amount();
					}

				}
				"""))) {
			app.getOk("/cart/put");

			app.reloadSuccessfully();

			assertThat(app.getOk("/cart")).isEqualTo("7:300");
		}
	}

	/**
	 * 자식 bean이 시작할 때 부모의 정적 레지스트리에 리스너를 등록한다. 재시작이라면 레지스트리가 비워지지만,
	 * 재로딩에서는 정적 상태가 남는다.
	 */
	@Test
	@KnownIssue("부모 클래스의 정적 상태는 재로딩으로 초기화되지 않아 이전 세대의 리스너가 계속 호출된다")
	void parentStaticRegistryDoesNotKeepPreviousGenerationListeners() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(NOTIFIER, SUBSCRIBER, NOTIFY_CONTROLLER))) {
			assertThat(app.getOk("/notify")).isEqualTo("1");

			app.reloadSuccessfully();

			assertThat(app.getOk("/notify")).as("listeners invoked").isEqualTo("1");
		}
	}

	@Test
	@KnownIssue("부모의 정적 레지스트리에 남은 자식 리스너(람다)가 이전 세대 클래스로더를 붙잡는다")
	void parentStaticRegistryDoesNotPinPreviousGenerations() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(NOTIFIER, SUBSCRIBER, NOTIFY_CONTROLLER))) {
			app.assertPreviousGenerationsCollected(() -> app.getOk("/notify"));
		}
	}

	@Test
	@KnownIssue("부모 정적 캐시에 넣은 자식 객체는 이전 세대 클래스라 재로딩 뒤 ClassCastException")
	void childObjectInParentStaticCacheIsUsableAfterReload() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(SIMPLE_CACHE, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.SimpleCache;

				@RestController
				public class CacheController {

					@GetMapping("/cache/put")
					public String put() {
						SimpleCache.put("cart", new CartView("apple"));
						return "ok";
					}

					@GetMapping("/cache/get")
					public String get() {
						CartView view = (CartView) SimpleCache.get("cart");
						return view.item();
					}

					public record CartView(String item) {
					}

				}
				"""))) {
			app.getOk("/cache/put");

			app.reloadSuccessfully();

			assertThat(app.getOk("/cache/get")).isEqualTo("apple");
		}
	}

	@Test
	@KnownIssue("부모 유틸리티의 Class 키 캐시에 자식 클래스가 남아 이전 세대를 붙잡는다")
	void parentClassKeyedReflectionCacheDoesNotPinPreviousGenerations() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(REFLECTION_MAPPER, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.ReflectionMapper;

				@RestController
				public class ProductController {

					@GetMapping("/product")
					public String product() throws ReflectiveOperationException {
						return ReflectionMapper.toMap(new ProductView("pen", 1000)).toString();
					}

					public record ProductView(String name, long price) {
					}

				}
				"""))) {
			assertThat(app.getOk("/product")).isEqualTo("{name=pen, price=1000}");

			app.assertPreviousGenerationsCollected(() -> app.getOk("/product"));
		}
	}

	@Test
	@KnownIssue("부모의 ThreadLocal에 넣고 비우지 않은 자식 객체가 Tomcat 요청 스레드에 남아 이전 세대를 붙잡는다")
	void parentThreadLocalDoesNotPinPreviousGenerations() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(REQUEST_CONTEXT, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.RequestContext;

				@RestController
				public class ContextController {

					@GetMapping("/context")
					public String context() {
						RequestContext.set(new RequestInfo("trace-1"));
						return ((RequestInfo) RequestContext.get()).traceId();
					}

					public record RequestInfo(String traceId) {
					}

				}
				"""))) {
			assertThat(app.getOk("/context")).isEqualTo("trace-1");

			app.assertPreviousGenerationsCollected(() -> app.getOk("/context"));
		}
	}

	@Test
	@KnownIssue("부모의 정적 스레드 풀 스레드가 생성 시 요청 스레드의 TCCL(세대 클래스로더)을 물려받아 그 세대를 붙잡는다")
	void parentStaticExecutorDoesNotPinPreviousGenerations() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(DOMAIN_EXECUTOR, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.DomainExecutor;

				@RestController
				public class WorkController {

					@GetMapping("/work")
					public String work() throws Exception {
						return DomainExecutor.submit(() -> "done").get();
					}

				}
				"""))) {
			assertThat(app.getOk("/work")).isEqualTo("done");

			app.assertPreviousGenerationsCollected(() -> app.getOk("/work"));
		}
	}

	/**
	 * 부모 코드가 {@code Class.forName}으로 자식 클래스를 만들면 부모 클래스로더가 같은 디렉터리에서 그 클래스를
	 * 따로 로드한다(유령 사본). 재로딩되지 않고 자식 세대의 클래스와 별개다.
	 */
	@Test
	@KnownIssue("부모 코드의 Class.forName은 부모 클래스로더로 자식 클래스의 유령 사본을 로드한다 (4장)")
	void parentFactoryLoadingClassByNameCreatesCurrentGenerationType() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, DISCOUNT_POLICY, PLUGIN_FACTORY, FREE_SHIPPING, """
				package com.example.app.web;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				import com.example.app.domain.DiscountPolicy;
				import com.example.app.domain.PluginFactory;

				@RestController
				public class PluginController {

					@GetMapping("/plugin")
					public String plugin() throws ReflectiveOperationException {
						DiscountPolicy policy = (DiscountPolicy) PluginFactory.create("com.example.app.web.FreeShipping");
						return (policy.getClass() == FreeShipping.class) ? "current-generation" : "other-copy";
					}

				}
				"""))) {
			assertThat(app.getOk("/plugin")).isEqualTo("current-generation");
		}
	}

	@Test
	@KnownIssue("공용 ForkJoinPool 스레드의 TCCL은 시스템 클래스로더라서 자식 클래스를 못 찾거나(이 하네스), 시스템 "
			+ "클래스패스의 출력 디렉터리에서 유령 사본을 로드한다(실제 앱)")
	void parentCodeUsingTcclOnCommonPoolResolvesCurrentGenerationClass() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(MONEY, DISCOUNT_POLICY, ASYNC_CLASS_RESOLVER, FREE_SHIPPING,
				"""
						package com.example.app.web;

						import org.springframework.web.bind.annotation.GetMapping;
						import org.springframework.web.bind.annotation.RestController;

						import com.example.app.domain.AsyncClassResolver;

						@RestController
						public class ResolveController {

							@GetMapping("/resolve")
							public String resolve() {
								ClassLoader loader = AsyncClassResolver.loaderOf("com.example.app.web.FreeShipping");
								return (loader == getClass().getClassLoader()) ? "current-generation" : String.valueOf(loader);
							}

						}
						"""))) {
			assertThat(app.getOk("/resolve")).isEqualTo("current-generation");
		}
	}

	/**
	 * 부모 패키지의 코드가 실수로 자식 타입(예: 컨트롤러 DTO)을 받는 경우. 같은 소스 세트라 컴파일은 된다.
	 */
	@Test
	void boundaryCheckerWarnsWhenParentCodeReferencesChildType(CapturedOutput output) throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(LEGACY_FORMATTER, ORDER_VIEW, FORMAT_CONTROLLER))) {
			assertThat(output.getOut()).contains("Parent-owned classes reference reloadable (child-owned) classes")
				.contains("com.example.app.domain.LegacyFormatter -> [com.example.app.web.OrderView]");
		}
	}

	@Test
	@KnownIssue("부모 클래스가 참조하는 자식 타입은 부모 클래스로더가 따로 로드해서 자식이 넘긴 객체와 타입이 맞지 "
			+ "않는다(LinkageError)")
	void parentCodeReferencingChildTypeWorks() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(LEGACY_FORMATTER, ORDER_VIEW, FORMAT_CONTROLLER))) {
			assertThat(app.getOk("/format")).isEqualTo("[order-1]");
		}
	}

	/**
	 * 자식 클래스가 {@code @SpringBootApplication} 클래스와 같은 패키지에 있으면서 그 클래스의 package-private
	 * 멤버를 쓴다. 클래스로더가 다르면 런타임 패키지가 달라 접근할 수 없다.
	 */
	@Test
	@KnownIssue("부모 소유 애플리케이션 클래스와 자식 클래스는 이름이 같은 패키지여도 런타임 패키지가 달라 "
			+ "package-private 접근이 IllegalAccessError로 실패한다")
	void childClassCanUsePackagePrivateMembersOfApplicationClass() throws Exception {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put(ConsumerApp.APPLICATION_CLASS, """
				package com.example.app;

				import org.springframework.boot.autoconfigure.SpringBootApplication;

				@SpringBootApplication
				public class DemoApplication {

					static String displayName() {
						return "demo";
					}

				}
				""");
		sources.putAll(sources("""
				package com.example.app;

				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class InfoController {

					@GetMapping("/info")
					public String info() {
						return DemoApplication.displayName();
					}

				}
				"""));
		try (ConsumerApp app = ConsumerApp.start(sources)) {
			assertThat(app.getOk("/info")).isEqualTo("demo");
		}
	}

	/**
	 * 부모 패키지에 Spring 코드를 두지 않으면 {@code @EnableCaching} 같은 설정도 자식에 있다. 부모의
	 * {@code CacheAutoConfiguration}은 {@code @EnableCaching}이 부모에 있을 때만 {@code CacheManager}를 만든다.
	 */
	@Test
	@KnownIssue("자식의 @EnableCaching은 부모 CacheAutoConfiguration이 보지 못해 CacheManager가 없고 세대가 뜨지 "
			+ "못한다 (6장)")
	void cachingEnabledInChildConfigurationWorks() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources("""
				package com.example.app.web;

				import org.springframework.cache.annotation.EnableCaching;
				import org.springframework.context.annotation.Configuration;

				@Configuration(proxyBeanMethods = false)
				@EnableCaching
				public class CacheConfig {
				}
				""", """
				package com.example.app.web;

				import java.util.concurrent.atomic.AtomicInteger;

				import org.springframework.cache.annotation.Cacheable;
				import org.springframework.web.bind.annotation.GetMapping;
				import org.springframework.web.bind.annotation.RestController;

				@RestController
				public class CachedController {

					private final AtomicInteger calls = new AtomicInteger();

					@GetMapping("/cached")
					@Cacheable("cached")
					public String cached() {
						return "call-" + this.calls.incrementAndGet();
					}

				}
				"""))) {
			assertThat(app.getOk("/cached")).isEqualTo("call-1");
			assertThat(app.getOk("/cached")).isEqualTo("call-1");
		}
	}

	private static final String SUBSCRIBER = """
			package com.example.app.web;

			import jakarta.annotation.PostConstruct;

			import org.springframework.stereotype.Component;

			import com.example.app.domain.Notifier;

			@Component
			public class NotificationSubscriber {

				private int received;

				@PostConstruct
				void subscribe() {
					Notifier.register((message) -> this.received++);
				}

			}
			""";

	private static final String NOTIFY_CONTROLLER = """
			package com.example.app.web;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			import com.example.app.domain.Notifier;

			@RestController
			public class NotifyController {

				@GetMapping("/notify")
				public int notifyListeners() {
					return Notifier.fire("event");
				}

			}
			""";

	private static final String LEGACY_FORMATTER = """
			package com.example.app.domain;

			import com.example.app.web.OrderView;

			public final class LegacyFormatter {

				private LegacyFormatter() {
				}

				public static String format(OrderView view) {
					return "[" + view.label() + "]";
				}

			}
			""";

	private static final String ORDER_VIEW = """
			package com.example.app.web;

			public record OrderView(String label) {
			}
			""";

	private static final String FORMAT_CONTROLLER = """
			package com.example.app.web;

			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			import com.example.app.domain.LegacyFormatter;

			@RestController
			public class FormatController {

				@GetMapping("/format")
				public String format() {
					return LegacyFormatter.format(new OrderView("order-1"));
				}

			}
			""";

}
