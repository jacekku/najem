package pl.najem.um.adapter.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The exact negation of {@link IssuerConfigured}, so that precisely one posture registers.
 *
 * <p>Written as the negation of the other condition rather than as its own reading of the property:
 * two independent implementations of "is an issuer configured" could disagree, and the way they
 * would disagree is by both being false — no filter chain at all, which is Boot's default chain and
 * a posture nobody chose.
 */
public class IssuerNotConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !new IssuerConfigured().matches(context, metadata);
    }
}
