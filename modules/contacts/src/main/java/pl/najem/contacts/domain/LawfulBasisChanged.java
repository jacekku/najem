package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Why the agency now holds this person's data. Retention rules key on the basis, so a signed tenant
 * left on {@code legitimate-interest} makes the RODO record say something untrue.
 */
public record LawfulBasisChanged(UUID workspaceId, UUID contactId, String lawfulBasis,
                                 LocalDate changedOn) {
}
