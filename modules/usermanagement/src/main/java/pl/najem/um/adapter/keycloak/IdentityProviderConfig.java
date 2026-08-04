package pl.najem.um.adapter.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.najem.um.application.KeycloakAdminPort;

/**
 * Chooses the identity-provider adapter.
 *
 * <p>When {@code najem.keycloak.base-url} is set, that is the real Keycloak admin API. When it is
 * not — module tests, the walking skeleton, any local run without an IdP — the port is still present
 * but refuses to provision, so the application context starts while user creation fails loudly
 * instead of silently inventing accounts.
 *
 * <p><b>The remaining three values carry no defaults</b> (roadmap rule 7; coordinator ruling at
 * najem-build seq 239/242). {@code admin-password} used to default to {@code "admin"}, and it was
 * in the packaged profile: a deployment that forgot to override it did not fail — it ran with a
 * guessable credential against the system that owns every account. A realm defaulting to
 * {@code "najem"} is the same shape, quieter: it would provision users into whichever realm
 * happened to bear that name.
 */
@Configuration
public class IdentityProviderConfig {

    @Bean
    KeycloakAdminPort keycloakAdminPort(
            @Value("${najem.keycloak.base-url:}") String baseUrl,
            @Value("${najem.keycloak.realm:}") String realm,
            @Value("${najem.keycloak.admin-username:}") String username,
            @Value("${najem.keycloak.admin-password:}") String password) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return email -> {
                throw new IllegalStateException(
                    "no identity provider configured: set najem.keycloak.base-url to provision users");
            };
        }
        // Having named an identity provider, the caller must say which realm and as whom. Guessing
        // any of the three would connect to a real IdP with values nobody chose.
        requireConfigured("najem.keycloak.realm", realm);
        requireConfigured("najem.keycloak.admin-username", username);
        requireConfigured("najem.keycloak.admin-password", password);
        return new KeycloakAdminAdapter(baseUrl, realm, username, password);
    }

    private static void requireConfigured(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Refusing to start: 'najem.keycloak.base-url' names an identity provider but '"
                    + property + "' is not set. NAJEM will not guess a credential or a realm for a "
                    + "system that owns every account.");
        }
    }
}
