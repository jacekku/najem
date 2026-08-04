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
 */
@Configuration
public class IdentityProviderConfig {

    @Bean
    KeycloakAdminPort keycloakAdminPort(
            @Value("${najem.keycloak.base-url:}") String baseUrl,
            @Value("${najem.keycloak.realm:najem}") String realm,
            @Value("${najem.keycloak.admin-username:admin}") String username,
            @Value("${najem.keycloak.admin-password:admin}") String password) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return email -> {
                throw new IllegalStateException(
                    "no identity provider configured: set najem.keycloak.base-url to provision users");
            };
        }
        return new KeycloakAdminAdapter(baseUrl, realm, username, password);
    }
}
