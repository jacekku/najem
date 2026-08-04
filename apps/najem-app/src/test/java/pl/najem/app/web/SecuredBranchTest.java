package pl.najem.app.web;

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
    // Replaces the real decoder's bean DEFINITION rather than competing with it. A second
    // @Primary bean would not help: SecurityConfig's decoder is eager on purpose, so a bad issuer
    // fails at boot rather than on the first request, and it would still try OIDC discovery
    // against issuer.invalid before anything could out-prioritise it.
    "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Testcontainers
class SecuredBranchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Test
    void anUnauthenticatedRequestIsRejectedRatherThanServed() throws Exception {
        int status = mvc.perform(get("/workspace")).andReturn().getResponse().getStatus();

        assertThat(status)
            .as("with an issuer configured every screen requires a token; this is the branch a "
                + "real deployment runs and nothing exercised it before")
            .isEqualTo(401);
    }

    /** Not 401 — the token authenticates; the refusal now comes from having no workspace. */
    @Test
    void anAuthenticatedRequestReachesTheWorkspaceSeam() throws Exception {
        int status = mvc.perform(get("/workspace").header("Authorization", "Bearer any-token"))
            .andReturn().getResponse().getStatus();

        assertThat(status)
            .as("the token authenticates; this subject simply belongs to no workspace")
            .isEqualTo(403);
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
