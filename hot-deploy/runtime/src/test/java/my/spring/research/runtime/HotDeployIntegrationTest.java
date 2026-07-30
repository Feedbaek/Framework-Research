package my.spring.research.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import my.spring.research.runtime.config.RuntimeRefreshCoordinator;
import my.spring.research.runtime.deploy.DeploymentManager;
import my.spring.research.runtime.deploy.DeploymentRouter;
import my.spring.research.runtime.loader.ArtifactValidationException;

@SpringBootTest(properties = {
		"spring.cloud.compatibility-verifier.enabled=false",
		"spring.security.user.password=test-only-password"
})
@AutoConfigureMockMvc
class HotDeployIntegrationTest {

	private static final Path REPOSITORY = createRepository();
	private static Path appArtifact;

	@Autowired
	private DeploymentManager deploymentManager;

	@Autowired
	private ConfigurableApplicationContext rootContext;

	@Autowired
	private DeploymentRouter deploymentRouter;

	@Autowired
	private RuntimeRefreshCoordinator refreshCoordinator;

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void hotDeployProperties(DynamicPropertyRegistry registry) {
		registry.add("runtime.hot-deploy.repository-root", REPOSITORY::toString);
	}

	@BeforeAll
	static void stageSampleApp() throws IOException {
		appArtifact = REPOSITORY.resolve("sample-app.jar");
		Files.copy(Path.of(System.getProperty("sampleAppJar")), appArtifact);
	}

	@Test
	void deploysSpringFreeAppWithoutRestartingRootContextAndKeepsItActiveAfterRejectedDeploy() throws Exception {
		String rootContextId = rootContext.getId();
		long rootStartupDate = rootContext.getStartupDate();

		var deployed = deploymentManager.deploy(appArtifact);

		assertThat(deployed.appId()).isEqualTo("sample-app");
		assertThat(deployed.version()).isEqualTo("1.0.0");
		mockMvc.perform(post("/api/business")
						.contentType("application/json")
						.content("""
								{"operation":"hello","attributes":{}}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("runtime:hello"))
				.andExpect(jsonPath("$.attributes.appVersion").value("1.0.0"));

		ClassLoader appClassLoader = deploymentRouter.activeSlot().orElseThrow().candidate().classLoader();
		refreshCoordinator.refresh(Map.of("runtime.message.prefix", "refreshed:"));

		mockMvc.perform(post("/api/business")
						.contentType("application/json")
						.content("""
								{"operation":"hello","attributes":{}}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("refreshed:hello"));
		assertThat(deploymentRouter.activeSlot().orElseThrow().candidate().classLoader())
				.isSameAs(appClassLoader);

		assertThatThrownBy(() -> deploymentManager.deploy(Path.of(System.getProperty("sampleAppJar"))))
				.isInstanceOf(ArtifactValidationException.class)
				.hasMessageContaining("outside repository root");

		assertThat(deploymentManager.status().active().deploymentId()).isEqualTo(deployed.deploymentId());
		assertThat(rootContext.getId()).isEqualTo(rootContextId);
		assertThat(rootContext.getStartupDate()).isEqualTo(rootStartupDate);
	}

	private static Path createRepository() {
		try {
			return Files.createTempDirectory("hot-deploy-integration-repository-");
		}
		catch (IOException ex) {
			throw new ExceptionInInitializerError(ex);
		}
	}
}
