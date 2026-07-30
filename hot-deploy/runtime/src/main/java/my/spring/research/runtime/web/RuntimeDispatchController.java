package my.spring.research.runtime.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import my.spring.research.runtime.api.BusinessRequest;
import my.spring.research.runtime.api.BusinessResponse;
import my.spring.research.runtime.deploy.DeploymentRouter;

@RestController
@RequestMapping("/api/business")
public class RuntimeDispatchController {

	private final DeploymentRouter deploymentRouter;

	public RuntimeDispatchController(DeploymentRouter deploymentRouter) {
		this.deploymentRouter = deploymentRouter;
	}

	@PostMapping
	public BusinessResponse dispatch(@RequestBody BusinessRequest request) {
		return deploymentRouter.route(request);
	}
}
