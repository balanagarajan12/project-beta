package com.etp.migration.openapi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Springdoc maps its welcome redirect to {@code springdoc.swagger-ui.path}. This application sets that to
 * {@code /swagger-ui}, so the legacy {@code /swagger-ui.html} URL would otherwise 404.
 */
@Controller
@ConditionalOnExpression("${etp.migration.openapi.enabled:false} and '${springdoc.swagger-ui.path:}'.equals('/swagger-ui')")
public class SwaggerUiHtmlRedirect {

	@GetMapping("/swagger-ui.html")
	public String redirect() {
		return "redirect:/swagger-ui/index.html";
	}
}
