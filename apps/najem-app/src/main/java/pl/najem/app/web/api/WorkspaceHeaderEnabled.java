package pl.najem.app.web.api;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code najem.test.workspace-header} is explicitly true.
 *
 * <p>Gates the one remaining reader of {@code X-Workspace-Id}. The direction is what makes it safe:
 * <b>absent the property the bean does not exist</b>, so a deployment acquires the header path by
 * choosing it and can never acquire it by forgetting something. A profile guard
 * ({@code @Profile("!prod")}) would be reachable by omission, which is exactly what rule 7 forbids.
 *
 * <p>Plain Spring rather than Boot's {@code @ConditionalOnProperty}, following
 * {@code TestEndpointsEnabled} — same reasoning, and being the same shape is worth more here than
 * being the shortest.
 *
 * <p>{@code NoProductionWorkspaceHeaderTest} asserts no packaged configuration turns it on.
 */
public class WorkspaceHeaderEnabled implements Condition {

    static final String PROPERTY = "najem.test.workspace-header";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Boolean.parseBoolean(context.getEnvironment().getProperty(PROPERTY));
    }
}
