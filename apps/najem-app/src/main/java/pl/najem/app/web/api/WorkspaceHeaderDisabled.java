package pl.najem.app.web.api;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The negation of {@link WorkspaceHeaderEnabled}, and deliberately written as one.
 *
 * <p>Delegating rather than re-reading the property means the two conditions cannot both be false —
 * which would leave an application with no way at all to resolve a workspace, starting cleanly and
 * failing on the first API request. Two independent readings of the same property is precisely how
 * that gap opens, so there is only one reading. Same shape as
 * {@code IssuerConfigured}/{@code IssuerNotConfigured} in usermanagement.
 */
public class WorkspaceHeaderDisabled implements Condition {

    private final WorkspaceHeaderEnabled enabled = new WorkspaceHeaderEnabled();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !enabled.matches(context, metadata);
    }
}
