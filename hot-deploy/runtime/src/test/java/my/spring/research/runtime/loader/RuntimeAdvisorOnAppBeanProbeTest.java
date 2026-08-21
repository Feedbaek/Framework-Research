package my.spring.research.runtime.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

import my.spring.research.runtime.api.AppModule;
import my.spring.research.runtime.api.BusinessHandler;
import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.RuntimeMessageProvider;
import my.spring.research.runtime.config.HotDeployProperties;

/**
 * Probes whether an Advisor that lives in the fixed runtime root context can advise beans created
 * later inside a per-deployment child context.
 */
class RuntimeAdvisorOnAppBeanProbeTest {

	@TempDir
	Path tempDir;

	private final List<String> intercepted = new ArrayList<>();

	@Test
	void currentClassLoaderPolicyBlocksProxyCreationForAppBeans() throws Exception {
		GenericApplicationContext parent = parentContextWithAdvisor();
		try (parent) {
			URLClassLoader appClassLoader = appClassLoader(true);
			try (appClassLoader) {
				AppModule module = new AppModuleLoader().load(appClassLoader);
				GenericApplicationContext child = childContext(parent, appClassLoader, module, true);
				try (child) {
					BeanCreationException failure =
							assertThrows(BeanCreationException.class, child::refresh);

					assertTrue(String.valueOf(failure.getMessage()).contains("SpringProxy"),
							"expected the app classloader to hide Spring AOP types, but got: "
									+ failure.getMessage());
				}
			}
		}
	}

	@Test
	void parentAdvisorAdvisesAppBeanOnceAopTypesAreVisibleAndCreatorIsInChild() throws Exception {
		GenericApplicationContext parent = parentContextWithAdvisor();
		try (parent) {
			URLClassLoader appClassLoader = appClassLoader(false);
			try (appClassLoader) {
				AppModule module = new AppModuleLoader().load(appClassLoader);
				GenericApplicationContext child = childContext(parent, appClassLoader, module, true);
				try (child) {
					child.refresh();

					// CandidateContextFactory looks the entry point up by its concrete class, which a
					// JDK proxy no longer matches. Enabling AOP breaks that lookup.
					assertThrows(NoSuchBeanDefinitionException.class,
							() -> child.getBean(module.entryPoint()),
							"a JDK proxy unexpectedly still matched the concrete entry point type");

					BusinessHandler handler = child.getBean(BusinessHandler.class);
					handler.handle(new BusinessRequest("hello"));

					assertTrue(AopUtils.isAopProxy(handler), "app bean was not proxied");
					assertTrue(intercepted.contains("handle"),
							"advisor defined in the runtime root context did not advise the app bean");
				}
			}
		}
	}

	@Test
	void withoutAutoProxyCreatorInChildTheAppBeanIsNotProxiedButParentBeansStillAdvise() throws Exception {
		GenericApplicationContext parent = parentContextWithAdvisor();
		try (parent) {
			URLClassLoader appClassLoader = appClassLoader(false);
			try (appClassLoader) {
				AppModule module = new AppModuleLoader().load(appClassLoader);
				GenericApplicationContext child = childContext(parent, appClassLoader, module, false);
				try (child) {
					child.refresh();
					BusinessHandler handler = child.getBean(module.entryPoint());

					handler.handle(new BusinessRequest("hello"));

					assertFalse(AopUtils.isAopProxy(handler),
							"a BeanPostProcessor in the parent must not reach child context beans");
					assertFalse(intercepted.contains("handle"), "app bean method must not be advised");
					assertTrue(intercepted.contains("messageFor"),
							"parent-owned beans stay advised even when the app calls them");
				}
			}
		}
	}

	private GenericApplicationContext parentContextWithAdvisor() {
		GenericApplicationContext parent = new GenericApplicationContext();
		parent.registerBean(RuntimeMessageProvider.class, () -> operation -> "runtime message for " + operation);
		parent.registerBean("runtimeProbeAdvisor", Advisor.class, () -> new DefaultPointcutAdvisor(
				Pointcut.TRUE,
				(MethodInterceptor) invocation -> {
					intercepted.add(invocation.getMethod().getName());
					return invocation.proceed();
				}));
		parent.registerBeanDefinition("parentAutoProxyCreator",
				new RootBeanDefinition(DefaultAdvisorAutoProxyCreator.class));
		parent.refresh();
		return parent;
	}

	private GenericApplicationContext childContext(
			GenericApplicationContext parent,
			URLClassLoader appClassLoader,
			AppModule module,
			boolean registerAutoProxyCreator) {
		GenericApplicationContext child = new GenericApplicationContext();
		child.setParent(parent);
		child.setClassLoader(appClassLoader);
		if (registerAutoProxyCreator) {
			child.registerBeanDefinition("childAutoProxyCreator",
					new RootBeanDefinition(DefaultAdvisorAutoProxyCreator.class));
		}
		for (Class<?> component : module.components()) {
			RootBeanDefinition definition = new RootBeanDefinition(component);
			definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			child.registerBeanDefinition("app:" + component.getName(), definition);
		}
		return child;
	}

	/**
	 * @param enforceRuntimePolicy {@code true} uses the production {@link AppClassLoader} policy that
	 * hides {@code org.springframework.*}; {@code false} uses a plain parent-first loader that can
	 * see Spring AOP types.
	 */
	private URLClassLoader appClassLoader(boolean enforceRuntimePolicy) throws Exception {
		HotDeployProperties properties = new HotDeployProperties();
		properties.setRepositoryRoot(tempDir.resolve("repository"));
		Path repository = tempDir.resolve("repository");
		Files.createDirectories(repository);
		Path artifact = repository.resolve("sample-app.jar");
		if (!Files.exists(artifact)) {
			Files.copy(Path.of(System.getProperty("sampleAppJar")), artifact);
		}
		AppArtifact validated = new ArtifactValidator(properties).validate(artifact);
		if (enforceRuntimePolicy) {
			return new AppClassLoaderFactory().create(validated);
		}
		return new URLClassLoader(
				"app-spring-visible",
				new URL[] { validated.path().toUri().toURL() },
				getClass().getClassLoader());
	}
}
