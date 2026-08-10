package pl.najem.app.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who else is on the reservation, carried in the query string so the screen stays bookmarkable and
 * needs no session.
 *
 * <p>The confirmed lead is NOT here — they come from {@code interestId}, which is why the screen was
 * opened at all. These are the co-tenants and guarantors added on it.
 *
 * <p>A malformed id is refused rather than skipped, on the same grounds as
 * {@link UnitScreenController#chosen}: a garbled value silently dropping a party would produce a
 * reservation naming fewer people than the manager saw on the screen they submitted.
 */
public record PartiesDraft(List<UUID> tenants, List<UUID> guarantors) {

    public static PartiesDraft of(List<String> tenants, List<String> guarantors) {
        Set<UUID> seen = new LinkedHashSet<>();
        return new PartiesDraft(parse(tenants, seen), parse(guarantors, seen));
    }

    /**
     * {@code chosen} returns empty for blank, and a blank party id is as much a mistake as a
     * malformed one, so both become {@link InvalidContactIdException}.
     */
    private static List<UUID> parse(List<String> raw, Set<UUID> seen) {
        List<UUID> parsed = new ArrayList<>();
        if (raw == null) {
            return List.copyOf(parsed);
        }
        for (String value : raw) {
            UUID id = UnitScreenController.chosen(value)
                .orElseThrow(() -> new InvalidContactIdException(value, null));
            if (!seen.add(id)) {
                throw new DuplicatePartyException(id);
            }
            parsed.add(id);
        }
        return List.copyOf(parsed);
    }
}
