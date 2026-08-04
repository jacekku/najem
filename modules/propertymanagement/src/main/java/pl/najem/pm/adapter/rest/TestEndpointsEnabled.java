package pl.najem.pm.adapter.rest;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code najem.pm.test-endpoints.enabled} is explicitly true.
 *
 * <p>Plain Spring rather than Boot's {@code @ConditionalOnProperty} because a module must not
 * put spring-boot-autoconfigure on the application classpath — the same class of accident as
 * usermanagement putting spring-security there (seq 63).
 *
 * <p>The direction is the point: absent the property the bean does not exist. A profile-based
 * guard ({@code @Profile("!prod")}) would be reachable by omission, which is what rule 7 forbids.
 */
public class TestEndpointsEnabled implements Condition {

    static final String PROPERTY = "najem.pm.test-endpoints.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Boolean.parseBoolean(context.getEnvironment().getProperty(PROPERTY));
    }
}
