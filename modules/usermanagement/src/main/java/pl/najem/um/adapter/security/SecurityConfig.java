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
 * resource server and every endpoint but invitation acceptance requires a token. Without it —
 * local runs, module tests, e2e — everything is permitted, exactly as before this module existed.
 * The token proves identity only; roles and membership resolve from this module's own projection
 * (decision D1).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        String issuer = environment.getProperty(ISSUER_URI);
        http.csrf(csrf -> csrf.disable());

        if (issuer == null || issuer.isBlank()) {
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
