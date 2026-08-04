package pl.najem.reporting.adapter.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ResourceLoader;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rebuild endpoint exists only where a deployment has asked for it. Replaying every projection
 * is a denial of service anyone could trigger, and it used to be reachable unauthenticated by
 * anybody who could reach the port (najem-reviewer, seq 248).
 * <p>
 * <b>Two assertions, because either alone can be satisfied while the endpoint is wide open.</b> The
 * condition could be correct on a controller that never consults it, or the annotation could be
 * present naming a condition that returns true by default. The pairing is what ties them together.
 * <p>
 * The e2e assertion that the route actually 404s is new e2e logic rather than a forced edit, so it
 * is held for @najem-coordinator under rule 2d rather than riding along with this commit. This test
 * is what keeps the gate covered meanwhile — it is genuinely weaker, because it asserts the wiring
 * rather than the served response, and I would rather say so than let the pair of them read as
 * equivalent.
 */
class RebuildGateTest {

    /**
     * The properties this test sets, in a real {@link StandardEnvironment}. No Mockito and no
     * spring-test: reporting has neither, and @najem-accounting's seq 222 note is right that a
     * wiring test is not worth a new dependency when a stub is a few lines.
     */
    private static final Map<String, Object> PROPERTIES = new HashMap<>();

    private static ConditionContext contextOver(Map<String, Object> properties) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return new ConditionContext() {
            @Override
            public BeanDefinitionRegistry getRegistry() {
                return null;
            }

            @Override
            public ConfigurableListableBeanFactory getBeanFactory() {
                return null;
            }

            @Override
            public Environment getEnvironment() {
                return environment;
            }

            @Override
            public ResourceLoader getResourceLoader() {
                return null;
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }

    @Test
    void theEndpointIsOffUnlessTheDeploymentNamesTheProperty() {
        PROPERTIES.clear();
        assertThat(new RebuildEnabled().matches(contextOver(PROPERTIES), null))
            .as("absent property must mean no bean: a rebuild endpoint that exists by default is "
                + "an unauthenticated DoS on every projection")
            .isFalse();

        PROPERTIES.put(RebuildEnabled.PROPERTY, "false");
        assertThat(new RebuildEnabled().matches(contextOver(PROPERTIES), null))
            .as("explicitly false must also mean off, not merely 'not true'")
            .isFalse();

        PROPERTIES.put(RebuildEnabled.PROPERTY, "true");
        assertThat(new RebuildEnabled().matches(contextOver(PROPERTIES), null))
            .as("and it must still be switchable on, or the operator action is simply gone")
            .isTrue();
    }

    /**
     * Without this, the condition above can be perfectly correct while nothing consults it — which
     * is exactly how the endpoint got here, carrying a javadoc that described a gate it did not have.
     */
    @Test
    void theRebuildControllerActuallyConsultsThatCondition() {
        var conditional = RebuildController.class.getAnnotation(Conditional.class);

        assertThat(conditional)
            .as("RebuildController must be @Conditional, or the property gates nothing")
            .isNotNull();
        assertThat(conditional.value()).containsExactly(RebuildEnabled.class);
    }

    /**
     * And the read controller must not quietly regrow one. The rebuild lived there before, and a
     * method added back to it would be registered unconditionally — the gate is the separate bean,
     * so a method on the always-present controller bypasses it entirely.
     */
    @Test
    void theReadControllerExposesNoRebuild() {
        assertThat(ReportingController.class.getDeclaredMethods())
            .as("rebuild belongs on the conditional controller; a method here is ungated by "
                + "construction, whatever its own annotations say")
            .noneMatch(m -> m.getName().toLowerCase().contains("rebuild"));
    }
}
