package pl.najem.app.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import pl.najem.app.web.api.ApiWorkspaceResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the argument resolvers that hand a request its workspace.
 *
 * <p>Two of them, and between them they are the only ways a workspace is decided anywhere in this
 * application: {@link WebWorkspaceArgumentResolver} for the screens, and whichever one
 * {@code ApiWorkspaceConfig} selected for the modules' APIs. No controller reads a workspace from
 * a request, so none can be handed one its caller chose.
 *
 * <p><b>The {@code X-Workspace-Id} interceptor used to be registered here and is gone.</b> It
 * checked that a caller belonged to the workspace they named — correctly, and it caught a real
 * hole. It became unnecessary rather than wrong: nobody names a workspace now, so there is no claim
 * left to check. Keeping it would have meant maintaining a guard whose failure branch is
 * unreachable, which is the kind of code that later reads as protection somebody is relying on.
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
    private final ApiWorkspaceResolver apiWorkspaces;

    public WebConfig(WebWorkspaceArgumentResolver workspaces,
                     ApiWorkspaceResolver apiWorkspaces) {
        this.workspaces = workspaces;
        this.apiWorkspaces = apiWorkspaces;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(workspaces);
        resolvers.add(apiWorkspaces);
    }
}
