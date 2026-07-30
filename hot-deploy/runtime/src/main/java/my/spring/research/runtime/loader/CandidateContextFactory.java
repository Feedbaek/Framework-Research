package my.spring.research.runtime.loader;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

import my.spring.research.runtime.api.AppMetadata;
import my.spring.research.runtime.api.AppModule;
import my.spring.research.runtime.api.BusinessHandler;
import my.spring.research.runtime.config.HotDeployProperties;

@Component
public class CandidateContextFactory {

	private final ConfigurableApplicationContext parentContext;
	private final ArtifactValidator artifactValidator;
	private final AppClassLoaderFactory classLoaderFactory;
	private final AppModuleLoader moduleLoader;
	private final HotDeployProperties properties;

	public CandidateContextFactory(
			ConfigurableApplicationContext parentContext,
			ArtifactValidator artifactValidator,
			AppClassLoaderFactory classLoaderFactory,
			AppModuleLoader moduleLoader
	) {
		this.parentContext = parentContext;
		this.artifactValidator = artifactValidator;
		this.classLoaderFactory = classLoaderFactory;
		this.moduleLoader = moduleLoader;
		this.properties = artifactValidator.properties();
	}

	public LoadedCandidate load(Path artifactPath) {
		AppArtifact artifact = artifactValidator.validate(artifactPath);
		URLClassLoader classLoader = classLoaderFactory.create(artifact);
		GenericApplicationContext context = new GenericApplicationContext();
		Thread thread = Thread.currentThread();
		ClassLoader previous = thread.getContextClassLoader();
		try {
			thread.setContextClassLoader(classLoader);
			AppModule module = moduleLoader.load(classLoader);
			ValidatedModule validatedModule = validateModule(module, classLoader);
			context.setParent(parentContext);
			context.setClassLoader(classLoader);
			context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(false);
			registerComponents(context, artifact, validatedModule.components());
			context.refresh();
			BusinessHandler handler = context.getBean(validatedModule.entryPoint());
			return new LoadedCandidate(
					artifact,
					validatedModule.metadata(),
					context,
					classLoader,
					handler
			);
		}
		catch (RuntimeException | Error ex) {
			try {
				context.close();
			}
			catch (RuntimeException closeFailure) {
				ex.addSuppressed(closeFailure);
			}
			try {
				classLoader.close();
			}
			catch (Exception closeFailure) {
				ex.addSuppressed(closeFailure);
			}
			throw ex;
		}
		finally {
			thread.setContextClassLoader(previous);
		}
	}

	private ValidatedModule validateModule(AppModule module, ClassLoader classLoader) {
		if (module.getClass().getClassLoader() != classLoader) {
			throw new ArtifactValidationException("AppModule provider must be loaded by the app classloader");
		}
		AppMetadata metadata = module.metadata();
		if (!RuntimeApiCompatibility.includes(metadata.runtimeApiRange(), properties.getRuntimeApiVersion())) {
			throw new ArtifactValidationException("unsupported runtime-api range: " + metadata.runtimeApiRange());
		}
		List<Class<?>> declaredComponents = List.copyOf(module.components());
		Class<? extends BusinessHandler> entryPoint = module.entryPoint();
		if (entryPoint == null) {
			throw new ArtifactValidationException("entryPoint must not be null");
		}
		if (!BusinessHandler.class.isAssignableFrom(entryPoint)) {
			throw new ArtifactValidationException("entryPoint must implement BusinessHandler");
		}
		if (entryPoint.getClassLoader() != classLoader) {
			throw new ArtifactValidationException("entryPoint must be loaded by the app classloader");
		}
		if (!declaredComponents.contains(entryPoint)) {
			throw new ArtifactValidationException("entryPoint must be declared in components");
		}
		Set<Class<?>> components = new LinkedHashSet<>(declaredComponents);
		if (components.size() != declaredComponents.size()) {
			throw new ArtifactValidationException("components must not contain duplicates");
		}
		components.forEach(component -> validateComponent(component, classLoader));
		return new ValidatedModule(metadata, List.copyOf(components), entryPoint);
	}

	private void validateComponent(Class<?> component, ClassLoader classLoader) {
		if (component == null) {
			throw new ArtifactValidationException("component must not be null");
		}
		if (component.getClassLoader() != classLoader) {
			throw new ArtifactValidationException("component must be loaded by the app classloader: " + component.getName());
		}
		if (!Modifier.isPublic(component.getModifiers()) || Modifier.isAbstract(component.getModifiers())) {
			throw new ArtifactValidationException("component must be a concrete public class: " + component.getName());
		}
		if (component.getConstructors().length != 1) {
			throw new ArtifactValidationException("component must declare exactly one public constructor: " + component.getName());
		}
	}

	private void registerComponents(
			GenericApplicationContext context,
			AppArtifact artifact,
			List<Class<?>> components
	) {
		String beanNamePrefix = "app@" + artifact.sha256().substring(0, 12) + ":";
		for (Class<?> component : components) {
			RootBeanDefinition definition = new RootBeanDefinition(component);
			definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			context.registerBeanDefinition(beanNamePrefix + component.getName(), definition);
		}
	}

	private record ValidatedModule(
			AppMetadata metadata,
			List<Class<?>> components,
			Class<? extends BusinessHandler> entryPoint
	) {
	}
}
