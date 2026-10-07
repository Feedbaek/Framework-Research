package com.example.reload.generation;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.reload.fixture.ParentTransactionalService;
import com.example.reload.fixture.Probe;
import com.example.reload.fixture.ThreadBoundTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부모와 자식 사이의 트랜잭션 참여(전파 {@code REQUIRED}). 자식의 트랜잭션 인터셉터는 세대마다 새로 만들어지지만
 * 부모 {@code TransactionManager}와 부모 클래스로더의 {@code TransactionSynchronizationManager}를 쓰므로, 어느 쪽이
 * 열든 같은 스레드의 반대쪽은 그 트랜잭션에 참여해야 한다.
 * <p>
 * 부모는 자원을 스레드에 묶는 {@link ThreadBoundTransactionManager}, {@code @Transactional} bean
 * ({@link ParentTransactionalService}), 요청을 트랜잭션으로 감싸는 필터({@link TransactionScopeFilter})를 가진다.
 * 각 응답의 {@code tx<번호>:new|joined}는 그 메서드가 본 트랜잭션과, 인터셉터가 그것을 새로 열었는지 여부다.
 */
@SpringBootTest(classes = TransactionParticipationScenarioTest.ParentApplication.class,
		webEnvironment = WebEnvironment.RANDOM_PORT)
class TransactionParticipationScenarioTest extends AbstractReloadScenarioTest {

	private static final ControllerCompiler compiler = createCompiler("tx-participation");

	private static final String ORDER_SERVICE = """
			package com.example.app;

			import org.springframework.stereotype.Service;
			import org.springframework.transaction.annotation.Transactional;

			import com.example.reload.fixture.ParentTransactionalService;
			import com.example.reload.fixture.ThreadBoundTransactionManager;

			@Service
			public class OrderService {

				private final ThreadBoundTransactionManager transactions;

				private final ParentTransactionalService parent;

				public OrderService(ThreadBoundTransactionManager transactions, ParentTransactionalService parent) {
					this.transactions = transactions;
					this.parent = parent;
				}

				@Transactional
				public String describe() {
					return "child=" + this.transactions.describeCurrent();
				}

				@Transactional
				public String describeThenCallParent() {
					return "child=" + this.transactions.describeCurrent() + " " + this.parent.describe();
				}

				@Transactional
				public void callFailingParentIgnoringFailure() {
					try {
						this.parent.fail();
					}
					catch (IllegalStateException ex) {
						// 참여한 쪽의 실패를 삼켜도 트랜잭션은 rollback-only로 남는다.
					}
				}

				@Transactional
				public void fail() {
					throw new IllegalStateException("child failure");
				}

			}
			""";

	private static final String TX_CONTROLLER = """
			package com.example.app;

			import org.springframework.transaction.UnexpectedRollbackException;
			import org.springframework.web.bind.annotation.GetMapping;
			import org.springframework.web.bind.annotation.RestController;

			import com.example.reload.fixture.ParentTransactionalService;

			@RestController
			public class TxController {

				private final OrderService orders;

				private final ParentTransactionalService parent;

				public TxController(OrderService orders, ParentTransactionalService parent) {
					this.orders = orders;
					this.parent = parent;
				}

				@GetMapping("/child-starts")
				public String childStarts() {
					return this.orders.describeThenCallParent();
				}

				@GetMapping("/parent-starts")
				public String parentStarts() {
					return this.parent.describeThenCall(() -> this.orders.describe());
				}

				@GetMapping("/filter-starts/describe")
				public String filterStarts() {
					return this.orders.describe();
				}

				@GetMapping("/child-starts/parent-fails")
				public String childStartsParentFails() {
					try {
						this.orders.callFailingParentIgnoringFailure();
						return "committed";
					}
					catch (UnexpectedRollbackException ex) {
						return "unexpected-rollback";
					}
				}

				@GetMapping("/parent-starts/child-fails")
				public String parentStartsChildFails() {
					try {
						this.parent.callIgnoringFailure(() -> this.orders.fail());
						return "committed";
					}
					catch (UnexpectedRollbackException ex) {
						return "unexpected-rollback";
					}
				}

			}
			""";

	@Autowired
	private ThreadBoundTransactionManager transactions;

	@DynamicPropertySource
	static void reloadProperties(DynamicPropertyRegistry registry) {
		registerReloadProperties(registry, compiler);
	}

	@Override
	protected ControllerCompiler compiler() {
		return compiler;
	}

	@BeforeEach
	void deployChild() {
		deploy(Map.of("OrderService", ORDER_SERVICE, "TxController", TX_CONTROLLER));
		this.transactions.reset();
	}

	/**
	 * 자식 {@code @Transactional} 서비스가 트랜잭션을 열고 그 안에서 부모 {@code @Transactional} bean을 호출한다.
	 */
	@Test
	void parentBeanJoinsTransactionStartedByChild() {
		assertThat(getOk("/child-starts")).isEqualTo("child=tx1:new parent=tx1:joined");

		assertSingleTransaction(1, 0);
	}

	/**
	 * 부모 {@code @Transactional} bean이 트랜잭션을 열고 그 안에서 자식 콜백(자식 {@code @Transactional} 서비스)을
	 * 실행한다.
	 */
	@Test
	void childServiceJoinsTransactionStartedByParentBean() {
		assertThat(getOk("/parent-starts")).isEqualTo("parent=tx1:new child=tx1:joined");

		assertSingleTransaction(1, 0);
	}

	/**
	 * 부모 필터가 요청 전체를 트랜잭션으로 감싼다. {@code ReloadingDispatcherServlet}을 거쳐 자식 컨트롤러와
	 * 서비스로 이어지는 같은 요청 스레드다.
	 */
	@Test
	void childServiceJoinsTransactionStartedByParentFilter() {
		HttpResponse<String> response = get("/filter-starts/describe");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(response.headers().firstValue("X-Parent-Tx")).hasValue("tx1");
		assertThat(response.body()).isEqualTo("child=tx1:joined");
		// 필터는 응답이 나간 뒤에 커밋한다.
		await("filter commit", () -> this.transactions.committed() == 1);
		assertSingleTransaction(1, 0);
	}

	/**
	 * 참여한 부모 bean의 실패를 자식이 삼켜도, 자식이 연 트랜잭션은 커밋되지 않는다.
	 */
	@Test
	void parentFailureMarksTransactionStartedByChildRollbackOnly() {
		assertThat(getOk("/child-starts/parent-fails")).isEqualTo("unexpected-rollback");

		assertSingleTransaction(0, 1);
	}

	/**
	 * 참여한 자식 서비스의 실패를 부모가 삼켜도, 부모가 연 트랜잭션은 커밋되지 않는다.
	 */
	@Test
	void childFailureMarksTransactionStartedByParentRollbackOnly() {
		assertThat(getOk("/parent-starts/child-fails")).isEqualTo("unexpected-rollback");

		assertSingleTransaction(0, 1);
	}

	/**
	 * 새 세대의 인터셉터도 같은 부모 {@code TransactionManager}를 쓴다.
	 */
	@Test
	void participationWorksAfterReload() {
		getOk("/child-starts");
		getOk("/parent-starts");

		reload();

		assertThat(getOk("/child-starts")).isEqualTo("child=tx3:new parent=tx3:joined");
		assertThat(getOk("/parent-starts")).isEqualTo("parent=tx4:new child=tx4:joined");
		assertThat(this.transactions.begun()).isEqualTo(4);
		assertThat(this.transactions.committed()).isEqualTo(4);
	}

	/**
	 * 부모 bean에 자식 콜백을 넘기고 양쪽 인터셉터가 같은 트랜잭션을 다뤄도 이전 세대가 수거된다.
	 */
	@Test
	void participationDoesNotPinPreviousGenerations() throws InterruptedException {
		assertPreviousGenerationsCollected(() -> {
			getOk("/child-starts");
			getOk("/parent-starts");
			getOk("/filter-starts/describe");
			getOk("/child-starts/parent-fails");
			getOk("/parent-starts/child-fails");
		});
	}

	private void assertSingleTransaction(int committed, int rolledBack) {
		assertThat(this.transactions.begun()).as("physical transactions begun").isEqualTo(1);
		assertThat(this.transactions.committed()).as("commits").isEqualTo(committed);
		assertThat(this.transactions.rolledBack()).as("rollbacks").isEqualTo(rolledBack);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class ParentApplication {

		@Bean
		Probe probe() {
			return new Probe();
		}

		@Bean
		ThreadBoundTransactionManager transactionManager() {
			return new ThreadBoundTransactionManager();
		}

		@Bean
		ParentTransactionalService parentTransactionalService(ThreadBoundTransactionManager transactions) {
			return new ParentTransactionalService(transactions);
		}

		@Bean
		TransactionScopeFilter transactionScopeFilter(ThreadBoundTransactionManager transactions) {
			return new TransactionScopeFilter(transactions);
		}

	}

	/**
	 * {@code /filter-starts/**} 요청을 부모 트랜잭션으로 감싼다.
	 */
	static class TransactionScopeFilter extends OncePerRequestFilter {

		private final ThreadBoundTransactionManager transactions;

		TransactionScopeFilter(ThreadBoundTransactionManager transactions) {
			this.transactions = transactions;
		}

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return !request.getRequestURI().startsWith("/filter-starts/");
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			TransactionStatus status = this.transactions.getTransaction(TransactionDefinition.withDefaults());
			try {
				response.setHeader("X-Parent-Tx", "tx" + this.transactions.currentTransactionId());
				chain.doFilter(request, response);
			}
			catch (ServletException | IOException | RuntimeException ex) {
				this.transactions.rollback(status);
				throw ex;
			}
			this.transactions.commit(status);
		}

	}

}
