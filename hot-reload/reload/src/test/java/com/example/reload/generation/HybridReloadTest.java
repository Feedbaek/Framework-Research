package com.example.reload.generation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static com.example.reload.generation.ConsumerApp.sources;
import static org.assertj.core.api.Assertions.assertThat;

class HybridReloadTest {
	private static final String PARENT = """
		package com.example.app.domain;
		@org.springframework.stereotype.Component
		public class ExpensiveResource {
			private final String id = java.util.UUID.randomUUID().toString();
			public String value() { return "%s:" + id; }
		}
		""";
	private static final String WEB = """
		package com.example.app.web;
		@org.springframework.web.bind.annotation.RestController
		public class BusinessController {
			private final com.example.app.domain.ExpensiveResource resource;
			public BusinessController(com.example.app.domain.ExpensiveResource resource) { this.resource = resource; }
			@org.springframework.web.bind.annotation.GetMapping("/business")
			public String value() { return "%s:" + resource.value(); }
		}
		""";

	@Test
	void nestedCallsRemainOnRetiringGenerationUntilOperationFinishes() throws Throwable {
		try (ConsumerApp app = ConsumerApp.start(sources(PARENT.formatted("infra"), WEB.formatted("v1")))) {
			var previous = app.manager().current().context();
			app.manager().withGeneration((context) -> {
				assertThat(context).isSameAs(previous);
				app.reloadSuccessfully();
				assertThat(previous.isActive()).isTrue();
				app.manager().withGeneration((nested) -> {
					assertThat(nested).isSameAs(previous);
					return null;
				});
				return null;
			});
			assertThat(previous.isActive()).isFalse();
			assertThat(GenerationScope.current()).isNull();
		}
	}

	@Test
	void deletedBusinessTypesNeverFallBackToPreviouslyLoadedParentClasses() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(PARENT.formatted("infra"), WEB.formatted("v1")))) {
			String name = "com.example.app.web.BusinessController";
			// 의도적으로 부모에도 이전 클래스를 로드해 fallback 회귀를 재현한다.
			app.parentClass(name);
			java.nio.file.Path file = java.nio.file.Path.of(app.currentGenerationClassLoader()
					.getResource(name.replace('.', '/') + ".class").toURI());
			java.nio.file.Files.delete(file);
			app.reloadSuccessfully();
			assertThat(app.get("/business").statusCode()).isEqualTo(404);
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.currentGenerationClassLoader().loadClass(name))
					.isInstanceOf(ClassNotFoundException.class);
			app.reloadSuccessfully();
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.currentGenerationClassLoader().loadClass(name))
					.isInstanceOf(ClassNotFoundException.class);
		}
	}

	@Test
	void apiKeepsInfrastructureForBusinessButRejectsMixedChangesWithoutSupervisor() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(PARENT.formatted("infra-v1"), WEB.formatted("v1")),
				List.of("--reload.business-packages=com.example.app.web"))) {
			Object resource = app.context().getBean("expensiveResource");
			String first = app.getOk("/business");
			ClassLoader loader = app.currentGenerationClassLoader();
			app.update(sources(WEB.formatted("v2")));
			assertThat(app.postJson("/_reload", "{}").statusCode()).isEqualTo(200);
			assertThat(app.getOk("/business")).isEqualTo(first.replace("v1:infra", "v2:infra"));
			assertThat(app.context().getBean("expensiveResource")).isSameAs(resource);
			assertThat(app.currentGenerationClassLoader()).isNotSameAs(loader);
			int generation = app.manager().currentGenerationId();
			app.update(sources(PARENT.formatted("infra-v2"), WEB.formatted("v3")));
			var response = app.postJson("/_reload", "{}");
			assertThat(response.statusCode()).isEqualTo(409);
			assertThat(response.body()).contains("restart-required", "ExpensiveResource.class");
			assertThat(app.manager().currentGenerationId()).isEqualTo(generation);
			assertThat(app.getOk("/business")).startsWith("v2:infra-v1:");
			// 거절된 인프라 변경은 baseline에 반영하지 않는다.
			assertThat(app.postJson("/_reload", "{}").statusCode()).isEqualTo(409);
		}
	}

	@Test
	void standardBootRemainsIntactWhenNoBusinessPackagesAreSelected() throws Exception {
		try (ConsumerApp app = ConsumerApp.start(sources(PARENT.formatted("infra"), WEB.formatted("v1")),
				List.of("--reload.business-packages="))) {
			assertThat(app.getOk("/business")).startsWith("v1:infra:");
			assertThat(app.context().containsLocalBean("businessController")).isTrue();
			assertThat(app.context().containsLocalBean("requestMappingHandlerAdapter")).isTrue();
			assertThat(app.manager().currentGenerationId()).isZero();
		}
	}

	@Test
	void mvcRegistrationsAdapterPreparesRequestBeforeControllerAndIsRecreated() throws Exception {
		String config = """
			package com.example.app.domain;
			@org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
			public class MvcConfig implements org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations {
				public org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
					return new org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter() {
						protected org.springframework.web.servlet.ModelAndView handleInternal(jakarta.servlet.http.HttpServletRequest request,
							jakarta.servlet.http.HttpServletResponse response, org.springframework.web.method.HandlerMethod method) throws Exception {
							request.setAttribute("prepared", "ready");
							return super.handleInternal(request, response, method);
						}
					};
				}
			}
			""";
		String controller = """
			package com.example.app.web;
			@org.springframework.web.bind.annotation.RestController
			public class Controller {
				@org.springframework.web.bind.annotation.GetMapping("/prepared")
				public String handle(jakarta.servlet.http.HttpServletRequest request) {
					if (request.getAttribute("prepared") == null) { throw new IllegalStateException("Missing request context"); }
					return "ok";
				}
			}
			""";
		try (ConsumerApp app = ConsumerApp.start(sources(config, controller))) {
			Object first = app.manager().current().context().getBean("requestMappingHandlerAdapter");
			assertThat(app.getOk("/prepared")).isEqualTo("ok");
			app.reloadSuccessfully();
			assertThat(app.manager().current().context().getBean("requestMappingHandlerAdapter")).isNotSameAs(first);
			assertThat(app.getOk("/prepared")).isEqualTo("ok");
		}
	}
}
