package pl.najem.um.adapter.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application-wide security posture.
 *
 * <p>This module puts spring-security on the classpath for the whole application, and Boot's default
 * chain would then demand authentication on EVERY endpoint — including other modules' APIs and the
 * walking skeleton. So this chain is not optional decoration: it is what keeps the app's behaviour
 * unchanged until an identity provider is actually configured.
 *
 * <p>With {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} set, NAJEM is an OIDC
 * resource server and every endpoint but invitation acceptance requires a token. The token proves
 * identity only; roles and membership resolve from this module's own projection (decision D1).
 *
 * <p><b>Without an issuer the application refuses to start</b> unless {@code najem.security.permit-all}
 * is explicitly {@code true} (roadmap rule 7, human ruling at najem-build seq 171). It used to fall
 * back to permit-all silently, which meant an unset environment variable or a typo'd config map made
 * the whole application — every module's endpoints, every workspace's data — public with no error.
 * That is fail-open: indistinguishable from correct behaviour until the day it is wrong.
 *
 * <p>The opt-in cannot be satisfied by omission, which is the property that matters. Refusing to
 * start is deliberately louder than booting and denying everything: an app that 401s everywhere
 * looks like a broken deployment and invites someone to "fix" it by turning security off, whereas
 * an app that will not start names exactly what is missing.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    /** Explicit, test-and-local-only opt-in. Absent or false means "refuse", never "permit". */
    static final String PERMIT_ALL = "najem.security.permit-all";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        String issuer = environment.getProperty(ISSUER_URI);
        http.csrf(csrf -> csrf.disable());

        if (issuer == null || issuer.isBlank()) {
            // Compared as a string on purpose. Binding to Boolean makes an unparseable value throw
            // Spring's "Invalid boolean value 'x'", which still refuses to start but explains
            // nothing about what NAJEM wanted. Absent, empty and garbled all mean "not opted in".
            if (!"true".equalsIgnoreCase(environment.getProperty(PERMIT_ALL, "").trim())) {
                throw new IllegalStateException(
                    "Refusing to start: no identity provider is configured and permit-all was not "
                        + "explicitly requested. Set '" + ISSUER_URI + "' to secure this deployment, "
                        + "or set '" + PERMIT_ALL + "=true' to run without security (tests and local "
                        + "development only). NAJEM will not choose the permissive option for you.");
            }
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http
            .authorizeHttpRequests(auth -> auth
                // Public by design: the invitation token IS the credential (decision D4) and the
                // invitee has no account yet — requiring one would make invite-only unusable.
                .requestMatchers("/api/um/invitations/accept").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}
