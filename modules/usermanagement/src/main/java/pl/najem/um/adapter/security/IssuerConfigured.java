package pl.najem.um.adapter.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when this deployment names an identity provider.
 *
 * <p>A hand-written condition rather than {@code @ConditionalOnProperty}, because the question is
 * "is there a usable issuer" and that annotation answers "is the key present and not false" — a
 * blank string satisfies it. An issuer set to empty is a deployment that believes it configured one.
 */
public class IssuerConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String issuer = context.getEnvironment().getProperty(SecurityConfig.ISSUER_URI);
        return issuer != null && !issuer.isBlank();
    }
}
