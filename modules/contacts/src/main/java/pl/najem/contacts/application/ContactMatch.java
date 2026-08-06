package pl.najem.contacts.application;

import java.util.UUID;

/**
 * A hit in the people half of {@code search}. {@code contactId} is what every other contacts
 * endpoint takes, so a hit is navigable rather than merely informative.
 *
 * <p>Top-level rather than nested in {@link ContactDirectory}, because the port returns it and the
 * service returns it and nesting it in either would make one of them reach into the other. The
 * component names are the wire names — this is a REST response body — so renaming one is a client
 * migration rather than a refactoring.
 */
public record ContactMatch(UUID contactId, String givenName, String surname, String email) {
}
