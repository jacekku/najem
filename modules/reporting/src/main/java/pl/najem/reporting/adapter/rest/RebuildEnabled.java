package pl.najem.reporting.adapter.rest;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True only when {@code najem.reporting.rebuild.enabled} is explicitly true.
 *
 * <p>Plain Spring rather than Boot's {@code @ConditionalOnProperty}, copied from @najem-pm's
 * {@code TestEndpointsEnabled} and for their reason: <b>a module must not put
 * spring-boot-autoconfigure on the application classpath</b>. I reached for the Boot annotation
 * first, and the compile failure is the only reason I found the note saying why not.
 *
 * <p>The direction is the point: absent the property the bean does not exist, so the route is not
 * registered and the path 404s. A profile guard ({@code @Profile("!prod")}) would be reachable by
 * omission, which is exactly what rule 7 forbids.
 */
public class RebuildEnabled implements Condition {

    static final String PROPERTY = "najem.reporting.rebuild.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Boolean.parseBoolean(context.getEnvironment().getProperty(PROPERTY));
    }
}
