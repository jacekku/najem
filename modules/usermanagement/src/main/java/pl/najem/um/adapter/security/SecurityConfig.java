package pl.najem.um.adapter.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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

    private final Environment environment;

    /**
     * Validating in the constructor is what makes "refuse to start" true. The checks used to live
     * inside the filter-chain bean, which was fine while there was one chain — now that the posture
     * decides WHICH chains exist, a check inside any one of them would be a check that a differently
     * configured deployment never runs.
     */
    public SecurityConfig(Environment environment) {
        this.environment = environment;
        refuseToStartIfTheDeploymentNamedNoPosture();
    }

    private void refuseToStartIfTheDeploymentNamedNoPosture() {
        if (issuerConfigured()) {
            // Boot's default validator checks signature, exp/nbf and iss — but NOT aud. Without
            // this, ANY token the realm mints for ANY client authenticates against NAJEM, so the
            // day that realm hosts a second application, that application's tokens are NAJEM
            // tokens (najem-reviewer finding #5). Required rather than optional: an issuer
            // configured without an audience is a deployment that believes it is secured and is not.
            if (environment.getProperty(AUDIENCE, "").trim().isEmpty()) {
                throw new IllegalStateException(
                    "Refusing to start: '" + ISSUER_URI + "' is set but '" + AUDIENCE + "' is not. "
                        + "Without an expected audience, every token this issuer mints for any client "
                        + "is accepted as a NAJEM token. Set '" + AUDIENCE + "' to this deployment's "
                        + "client id.");
            }
            return;
        }
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
    }

    private boolean issuerConfigured() {
        String issuer = environment.getProperty(ISSUER_URI);
        return issuer != null && !issuer.isBlank();
    }

    /**
     * No identity provider, and somebody said so explicitly. Everything is open.
     *
     * <p>CSRF is left on for the screens even here, so the protection is exercised by the local
     * runs and the test suite rather than only existing in the posture nobody develops against.
     */
    @Bean
    @Conditional(IssuerNotConfigured.class)
    SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * The API: bearer tokens, no login page, no session.
     *
     * <p>Ordered ahead of the browser chain and matched on {@code /api/**} so the two never
     * negotiate for the same request. Without the split, an unauthenticated API call would be
     * answered with a <b>302 to Keycloak's login form</b> — a redirect to HTML where a machine
     * client expected 401, which reads as a broken endpoint rather than as a missing token.
     */
    @Bean
    @Order(1)
    @Conditional(IssuerConfigured.class)
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/um/invitations/accept").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    /**
     * The screens: sign in at Keycloak, come back with a session.
     *
     * <p><b>NAJEM never sees a password.</b> Keycloak is the identity provider (human ruling) and
     * this is the authorization-code flow — {@code /login} is a NAJEM page carrying a link, and the
     * form that asks for credentials is Keycloak's own. Nothing here stores, hashes, compares or
     * resets a password, and there is no code path that could begin to.
     *
     * <p>The token is still trusted for exactly one claim, {@code sub}. Roles and agency membership
     * come from this module's projection (decision D1) — signing in proves who somebody is and
     * grants them nothing.
     *
     * <p>CSRF stays on here. This is the cookie-backed surface, and the session carries which agency
     * a person is acting in, so a forged request can change where their next action lands.
     */
    @Bean
    @Order(2)
    @Conditional(IssuerConfigured.class)
    SecurityFilterChain browserFilterChain(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> clients)
        throws Exception {
        // A secured deployment with no client registration can validate tokens and cannot sign
        // anybody in — so every screen answers 403 and the application looks broken rather than
        // misconfigured. Named here rather than left to Spring, whose own failure for this is a
        // NoSuchBeanDefinitionException for a type nobody set out to configure.
        if (clients.getIfAvailable() == null) {
            throw new IllegalStateException(
                "Refusing to start: '" + ISSUER_URI + "' is set, so the screens require a sign-in, "
                    + "but no OAuth2 client is registered. Set "
                    + "'spring.security.oauth2.client.registration.keycloak.client-id' and "
                    + "'spring.security.oauth2.client.provider.keycloak.issuer-uri' so people can "
                    + "actually log in.");
        }
        http
            .authorizeHttpRequests(auth -> auth
                // The sign-in page itself, and the static assets it needs to render. A login page
                // that requires being logged in is a redirect loop.
                //
                // /theme is here because the sign-in page carries the light/dark control, and a
                // person who cannot comfortably read a light screen meets that page before they
                // have a session. It sets one cookie naming a colour scheme, reads nothing, and
                // is still CSRF-protected by the browser chain.
                .requestMatchers("/login", "/theme", "/css/**", "/vendor/**",
                    "/favicon.ico", "/favicon.svg").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(login -> login
                // Ours rather than Spring's generated one, so an unauthenticated visitor meets a
                // NAJEM page in Polish rather than being bounced straight out to Keycloak with no
                // explanation of where they are going.
                .loginPage("/login")
                .defaultSuccessUrl("/", true))
            .logout(logout -> logout
                .logoutSuccessUrl("/login?wylogowano")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"));
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
