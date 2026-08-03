package pl.najem.um.application;

import java.util.UUID;

/**
 * Outbound port to the identity provider. Keycloak owns credentials and login only (decision D1):
 * this port never reads or writes roles, groups or workspace membership.
 */
public interface KeycloakAdminPort {

    /** Creates the user if absent and returns its Keycloak subject id; idempotent per email. */
    UUID provision(String email);
}
