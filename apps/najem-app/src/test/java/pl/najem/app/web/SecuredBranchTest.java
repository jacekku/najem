package pl.najem.app.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The secured branch of {@code SecurityConfig}, which until now no test in the repository executed
 * — all five e2e suites and every application test run the permit-all branch (najem-reviewer
 * findings part 3 §2). A mutation that survived was: delete the secured branch and return
 * permit-all unconditionally.
 *
 * <p>Supplies its own {@link JwtDecoder}, which suppresses Boot's OIDC discovery, so the deployed
 * posture can be exercised without a live identity provider. What is under test is the chain —
 * that a screen requires a token at all — not Nimbus's parsing. The audience rule is tested where
 * it lives, in {@code AudienceValidatorTest}, rather than by widening its visibility for a test.
 */
@SpringBootTest(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.invalid/realms/najem",
    "najem.security.audience=najem-app",
    // The screens now sign people in, so the secured posture needs a client registration as well
    // as a resource server -- it refuses to start without one, on purpose. Provider endpoints are
    // given explicitly rather than as an issuer-uri so that nothing attempts OIDC discovery
    // against a host that does not exist.
    "spring.security.oauth2.client.registration.keycloak.client-id=najem-app",
    "spring.security.oauth2.client.registration.keycloak.client-authentication-method=none",
    "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
    "spring.security.oauth2.client.registration.keycloak.scope=openid",
    // Boot defaults this only when the provider is given as an issuer-uri, and this test cannot
    // use one without attempting discovery against a host that does not resolve.
    "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
    "spring.security.oauth2.client.provider.keycloak.authorization-uri=https://issuer.invalid/auth",
    "spring.security.oauth2.client.provider.keycloak.token-uri=https://issuer.invalid/token",
    "spring.security.oauth2.client.provider.keycloak.user-info-uri=https://issuer.invalid/userinfo",
    "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=https://issuer.invalid/certs",
    "spring.security.oauth2.client.provider.keycloak.user-name-attribute=sub",
    // Replaces the real decoder's bean DEFINITION rather than competing with it. A second
    // @Primary bean would not help: SecurityConfig's decoder is eager on purpose, so a bad issuer
    // fails at boot rather than on the first request, and it would still try OIDC discovery
    // against issuer.invalid before anything could out-prioritise it.
    "spring.main.allow-bean-definition-overriding=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class SecuredBranchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    /**
     * A screen now offers the sign-in page rather than answering 401.
     *
     * <p>This assertion used to be {@code 401} and the change is deliberate: a person typing a URL
     * into a browser cannot do anything with a 401, and telling them to present a bearer token is
     * an instruction for a program. The refusal is identical — nothing is served — but it ends
     * somewhere a human can act.
     */
    @Test
    void anUnauthenticatedScreenOffersTheSignInPage() throws Exception {
        var response = mvc.perform(get("/workspace")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).endsWith("/login");
    }

    /**
     * The API keeps answering 401, and that is the reason the two chains exist.
     *
     * <p>Left to one chain, this call would be redirected to Keycloak's login form — HTML, 302,
     * where a machine client expected 401. It would read as a broken endpoint rather than as a
     * missing token, and it is the failure a single-chain configuration produces silently.
     */
    @Test
    void anUnauthenticatedApiCallIsRefusedRatherThanRedirected() throws Exception {
        var response = mvc.perform(get("/api/acc/board")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl())
            .as("a redirect here would send a machine client to a login page")
            .isNull();
    }

    /** The sign-in page itself must be reachable, or the redirect above is a loop. */
    @Test
    void theSignInPageIsReachableWithoutSigningIn() throws Exception {
        var response = mvc.perform(get("/login")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
            .as("a link to the identity provider, and no password field: NAJEM never sees one")
            .contains("/oauth2/authorization/keycloak")
            .doesNotContain("type=\"password\"");
    }

    /**
     * A bearer token does not sign anybody into the screens, and that is deliberate.
     *
     * <p>This assertion used to expect 403 — the token authenticated, and the workspace seam
     * refused. Now the screens are session-only: the browser chain has no resource server, so a
     * token presented to a screen is simply not a credential there and the person is offered the
     * sign-in page. Two surfaces, two ways of proving who you are, neither borrowing the other's.
     */
    @Test
    void aBearerTokenIsNotACredentialForTheScreens() throws Exception {
        var response = mvc.perform(get("/workspace").header("Authorization", "Bearer any-token"))
            .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).endsWith("/login");
    }

    /**
     * Where a bearer token IS a credential, it authenticates and the request reaches the seam —
     * which is the half of the original assertion worth keeping. Answering at all proves the token
     * was accepted; what it answers is this subject having no agencies rather than a refusal.
     */
    @Test
    void anAuthenticatedApiCallReachesTheWorkspaceSeam() throws Exception {
        var response = mvc.perform(get("/api/um/me").header("Authorization", "Bearer any-token"))
            .andReturn().getResponse();

        // 403, not 401, and not a redirect. The distinction is the whole point: the token was
        // accepted, so the request got past authentication and was answered by NAJEM's own rule —
        // this subject has no account, because accounts come from invitations and never from
        // presenting a valid token. A 401 would mean the token was rejected.
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @TestConfiguration
    static class StubIdentityProvider {

        /** Stands in for the realm so the chain can be exercised without one. */
        @Bean
        JwtDecoder audienceCheckingJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject("11111111-2222-3333-4444-555555555555")
                .audience(List.of("najem-app"))
                .claim("iss", "https://issuer.invalid/realms/najem")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        }
    }
}
