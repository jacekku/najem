package pl.najem.app.web.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import pl.najem.app.web.WebWorkspaceResolver;

/**
 * Decides, once, how a module's API learns which workspace a request acts in.
 *
 * <p>Exactly one of these beans exists in any application. The conditions are each other's negation
 * by delegation rather than by two readings of a property, so "neither" is not a state this can be
 * configured into — an application either resolves from the caller or reads the test header, and
 * cannot be started with no answer at all.
 */
@Configuration
public class ApiWorkspaceConfig {

    /**
     * The real one: the workspace comes from the caller's memberships.
     */
    @Bean
    @Conditional(WorkspaceHeaderDisabled.class)
    ApiWorkspaceResolver apiWorkspaceArgumentResolver(WebWorkspaceResolver resolver) {
        return new ApiWorkspaceArgumentResolver(resolver);
    }

    /**
     * The test one, present only where somebody set {@code najem.test.workspace-header=true}.
     */
    @Bean
    @Conditional(WorkspaceHeaderEnabled.class)
    ApiWorkspaceResolver headerWorkspaceArgumentResolver() {
        return new HeaderWorkspaceArgumentResolver();
    }
}
