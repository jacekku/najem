package pl.najem.um.domain;

/**
 * Workspace-scoped roles. NAJEM domain data — never Keycloak realm roles (decision D1).
 *
 * <p>Phase 1 has exactly two: the domain session decided the property OWNER is not a system user in
 * MVP, and owner statements / tax packs were cut from scope, so no OWNER, VIEWER or ACCOUNTANT role
 * exists yet (coordinator ruling, najem-build seq 56). The enum stays open for them to arrive with
 * owner access; nothing here assumes only two values.
 */
public enum Role {

    /** Manages members and invitations, plus everything a MANAGER can do. */
    ADMIN,

    /** Day-to-day operations: properties, tenancies, reconciliation. No member management. */
    MANAGER
}
