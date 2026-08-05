package pl.najem.um.adapter.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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

    /** Which {@code aud} this deployment accepts. Required whenever an issuer is configured. */
    static final String AUDIENCE = "najem.security.audience";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        String issuer = environment.getProperty(ISSUER_URI);

        // On for the screens, off for the API — because the two surfaces carry their authority
        // differently and CSRF is an attack on ambient authority specifically.
        //
        // /api/** authenticates with a bearer token, which a browser does NOT attach to a
        // cross-site request. A forged call there cannot authenticate, so protection buys nothing
        // and costs every machine client a 403 — a real regression traded for no security.
        //
        // The screens are the opposite case. The agency a person is working in lives in the
        // HttpSession, which IS cookie-backed, and cookies DO ride along cross-site. The forgeable
        // action is not "read data" or "write data" — it is silently changing WHICH agency the
        // victim's next action happens in. A manager follows a link, their session flips to their
        // other agency, and the payment they record next lands in books they did not choose.
        //
        // Every other control still passes while that happens: the token is valid, the audience
        // matches, and membership is re-checked on every request — because the victim really is a
        // member of both agencies. Nothing else in the stack asks whether the person INTENDED the
        // switch, and that is the only question CSRF protection answers. It is rule 7's harm — a
        // write into a workspace nobody named — reached by a route that needs no default at all.
        //
        // Found by najem-frontend, who asked why their form worked without a token.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));

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

        // Boot's default validator checks signature, exp/nbf and iss — but NOT aud. Without this,
        // ANY token the realm mints for ANY client authenticates against NAJEM, so the day that
        // realm hosts a second application, that application's tokens are NAJEM tokens
        // (najem-reviewer finding #5). Required rather than optional: an issuer configured without
        // an audience is a deployment that believes it is secured and is not.
        String audience = environment.getProperty(AUDIENCE, "").trim();
        if (audience.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to start: '" + ISSUER_URI + "' is set but '" + AUDIENCE + "' is not. "
                    + "Without an expected audience, every token this issuer mints for any client "
                    + "is accepted as a NAJEM token. Set '" + AUDIENCE + "' to this deployment's "
                    + "client id.");
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

    /**
     * Adds audience checking to whatever validation the configured issuer already implies.
     *
     * <p>Only registered when an issuer is set: under permit-all there is no decoder to customise,
     * and a bean that quietly did nothing would be worse than no bean.
     */
    @Bean
    @ConditionalOnProperty(ISSUER_URI)
    JwtDecoder audienceCheckingJwtDecoder(Environment environment) {
        String issuer = environment.getProperty(ISSUER_URI);
        String audience = environment.getProperty(AUDIENCE, "").trim();

        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new AudienceValidator(audience)));
        return decoder;
    }

    /** A token that does not name this deployment is not for this deployment. */
    record AudienceValidator(String expected) implements OAuth2TokenValidator<Jwt> {

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            var audiences = token.getAudience();
            // Absent, empty and not-containing all fail. Uncertainty is never a permission.
            if (audiences != null && audiences.contains(expected)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "the required audience '" + expected + "' is missing from this token",
                "https://tools.ietf.org/html/rfc6750#section-3.1"));
        }
    }
}
