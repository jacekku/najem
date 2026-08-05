package pl.najem.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the UI's own argument resolvers.
 *
 * <p>Deliberately does NOT touch {@code SecurityConfig}. That class is unconditional on purpose:
 * spring-security on the classpath with no chain registered is not "no security", it is Boot's
 * default — authenticate everything — so a config that declines to register would silently secure
 * four other modules' APIs and surface as someone else's e2e going 401. In a no-issuer local run
 * its permit-all branch looks like dead code, which is exactly why the frontend is the most likely
 * thing to erode it (najem-build seq 96, plan decision D).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final WebWorkspaceArgumentResolver workspaces;
    private final WorkspaceHeaderInterceptor headerCheck;

    public WebConfig(WebWorkspaceArgumentResolver workspaces, WorkspaceHeaderInterceptor headerCheck) {
        this.workspaces = workspaces;
        this.headerCheck = headerCheck;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(workspaces);
    }

    /**
     * Scoped to {@code /api/**} deliberately. The screens resolve their workspace through
     * {@link WebWorkspaceResolver} and never send the header — applying this to them would demand
     * of a screen the very thing the seam exists to stop it sending.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(headerCheck).addPathPatterns("/api/**");
    }
}
