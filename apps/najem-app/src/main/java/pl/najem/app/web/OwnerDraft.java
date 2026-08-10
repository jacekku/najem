package pl.najem.app.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who owns the property being created, carried in the query string so the screen stays bookmarkable
 * and needs no session.
 *
 * <p>Order is preserved because the share inputs on phase two line up against it positionally: the
 * n-th input is the n-th owner's share, and a reordering would silently reassign shares between
 * people.
 *
 * <p>A malformed id is refused rather than skipped, on the same grounds as {@link PartiesDraft}: a
 * garbled value silently dropping an owner would create a property owned by fewer people than the
 * manager saw on the screen they submitted, and nothing would say so.
 */
public record OwnerDraft(List<UUID> owners) {

    public static OwnerDraft of(List<String> raw) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<UUID> parsed = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                UUID id = UnitScreenController.chosen(value)
                    .orElseThrow(() -> new InvalidContactIdException(value, null));
                if (!seen.add(id)) {
                    throw new DuplicatePartyException(id);
                }
                parsed.add(id);
            }
        }
        return new OwnerDraft(List.copyOf(parsed));
    }
}
