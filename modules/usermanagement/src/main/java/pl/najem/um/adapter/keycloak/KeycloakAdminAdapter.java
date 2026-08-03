package pl.najem.um.adapter.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import pl.najem.um.application.KeycloakAdminPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to the Keycloak admin REST API using the direct-grant admin login. Owns its wire DTOs
 * (convention 4) and never reads or writes roles or groups (decision D1).
 */
@Component
@ConditionalOnProperty("najem.keycloak.base-url")
public class KeycloakAdminAdapter implements KeycloakAdminPort {

    /** Wire DTOs — Keycloak's shapes, not the domain's. */
    record TokenResponse(String access_token) {}
    record UserRepresentation(String id, String username, String email, Boolean enabled) {}

    private final RestClient http;
    private final String realm;
    private final String username;
    private final String password;

    public KeycloakAdminAdapter(@Value("${najem.keycloak.base-url}") String baseUrl,
                                @Value("${najem.keycloak.realm:najem}") String realm,
                                @Value("${najem.keycloak.admin-username:admin}") String username,
                                @Value("${najem.keycloak.admin-password:admin}") String password) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.username = username;
        this.password = password;
    }

    @Override
    public UUID provision(String email) {
        String token = adminToken();
        return findByEmail(token, email).orElseGet(() -> createUser(token, email));
    }

    private String adminToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", username);
        form.add("password", password);
        var response = http.post()
            .uri("/realms/master/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
        if (response == null || response.access_token() == null) {
            throw new IllegalStateException("Keycloak admin login failed");
        }
        return response.access_token();
    }

    private Optional<UUID> findByEmail(String token, String email) {
        List<UserRepresentation> found = http.get()
            .uri(uriBuilder -> uriBuilder.path("/admin/realms/{realm}/users")
                .queryParam("email", email).queryParam("exact", true).build(realm))
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(new ParameterizedTypeReference<List<UserRepresentation>>() {});
        if (found == null || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(found.getFirst().id()));
    }

    private UUID createUser(String token, String email) {
        http.post()
            .uri("/admin/realms/{realm}/users", realm)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("username", email, "email", email, "enabled", true,
                "requiredActions", List.of("UPDATE_PASSWORD")))
            .retrieve()
            .toBodilessEntity();
        return findByEmail(token, email)
            .orElseThrow(() -> new IllegalStateException("Keycloak did not return the created user"));
    }
}
