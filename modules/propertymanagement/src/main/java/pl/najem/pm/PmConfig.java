package pl.najem.pm;

import org.springframework.context.annotation.Configuration;

/**
 * PM's own wiring. Deliberately empty of infrastructure beans.
 *
 * <p>The {@link java.time.Clock} that PM's date-driven processes inject used to be declared here.
 * That made every other module's clock depend on PM being on the classpath — accounting could not
 * start its own context without it — and it collided the moment the composition root declared one
 * of its own. Shared infrastructure belongs to the composition root exactly once
 * ({@code PlatformConfig}); a module that starts a context in its own tests supplies one there,
 * explicitly, which is what a test is allowed to do.
 */
@Configuration
public class PmConfig {
}
