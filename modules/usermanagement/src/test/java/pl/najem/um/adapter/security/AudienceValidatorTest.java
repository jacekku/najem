package pl.najem.um.adapter.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot's default validator checks a token's signature, {@code exp}/{@code nbf} and {@code iss} —
 * but not {@code aud}. Without an audience check, any token the realm mints for any client
 * authenticates against NAJEM, so the day that realm hosts a second application, that
 * application's tokens are NAJEM tokens (najem-reviewer finding #5).
 *
 * <p>Every uncertain case here resolves to rejected, per roadmap rule 7(2).
 */
class AudienceValidatorTest {

    private final SecurityConfig.AudienceValidator validator =
        new SecurityConfig.AudienceValidator("najem-app");

    @Test
    void acceptsATokenNamingThisDeployment() {
        assertThat(validator.validate(withAudience(List.of("najem-app"))).hasErrors()).isFalse();
    }

    @Test
    void acceptsATokenNamingThisDeploymentAmongOthers() {
        assertThat(validator.validate(withAudience(List.of("other", "najem-app"))).hasErrors())
            .isFalse();
    }

    @Test
    void rejectsATokenMintedForAnotherClient() {
        var result = validator.validate(withAudience(List.of("some-other-app")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getDescription())
            .contains("najem-app");
    }

    @Test
    void rejectsATokenWithNoAudienceAtAll() {
        assertThat(validator.validate(withAudience(List.of())).hasErrors()).isTrue();
    }

    /** A near-miss is a miss: audience is compared exactly, never by prefix or contains. */
    @Test
    void rejectsAnAudienceThatMerelyResemblesThisOne() {
        assertThat(validator.validate(withAudience(List.of("najem-app-staging"))).hasErrors())
            .isTrue();
    }

    private static Jwt withAudience(List<String> audience) {
        var builder = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("11111111-2222-3333-4444-555555555555")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
        return audience.isEmpty() ? builder.build() : builder.audience(audience).build();
    }
}
