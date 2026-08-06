package pl.najem.um.domain.events;

/**
 * Everything that has ever happened to one NAJEM account. Stream type {@code "User"}.
 *
 * <p>Separate from {@link WorkspaceEvent} because they are separate streams, and a single sealed
 * set spanning both would let {@link pl.najem.um.domain.Workspace} switch on {@code UserRegistered}
 * — a case that cannot occur and that the compiler would then require somebody to write a branch
 * for. Two streams, two closed sets.
 *
 * <p>Holds no personal data: Keycloak owns credentials and profile (D1), Contacts owns person data.
 */
public sealed interface UserEvent permits UserRegistered, UserLinkedToContact {
}
