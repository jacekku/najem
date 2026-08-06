package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The erasure-due report, composed from the two stores it joins rather than from a list of answers.
 *
 * <p>It takes both doubles for the reason {@code InMemoryTenancies} takes the portfolio projection:
 * a fake that kept its own set of "due" contacts would be asserting what it was told, and could not
 * fail when the join is wrong (rule 13). Built this way, deleting the hold check here fails the same
 * test it fails in SQL.
 *
 * <p>The rule this and {@link RetentionHoldRepository#activeReasons} jointly state is that a contact
 * with any unreleased hold is not due. They once disagreed, in the direction of keeping personal
 * data past its retention date while reporting nothing to erase, which is why the double routes
 * through {@code activeReasons} rather than reimplementing the predicate.
 */
public class InMemoryErasureDue implements ErasureDueQuery {

    private final InMemoryContacts contacts;
    private final InMemoryRetentionHolds holds;

    public InMemoryErasureDue(InMemoryContacts contacts, InMemoryRetentionHolds holds) {
        this.contacts = contacts;
        this.holds = holds;
    }

    @Override
    public List<UUID> dueForErasure(UUID workspaceId, LocalDate asOf) {
        return contacts.retainedUntilOnOrBefore(workspaceId, asOf).stream()
            .filter(contactId -> !holds.anyActiveFor(workspaceId, contactId))
            .toList();
    }
}
