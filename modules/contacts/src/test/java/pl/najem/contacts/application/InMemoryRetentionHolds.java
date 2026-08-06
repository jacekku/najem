package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The hold register in a map, keyed the way the table is keyed.
 *
 * <p>The three-part key is the whole point. V40 keyed the table (contact, reason) and a contact who
 * was tenant or guarantor on two tenancies had one row for "ledger-referenced": the first tenancy
 * to close released it, and the person became erasable while the second tenancy's ledger was still
 * live. A double keyed the short way would agree with the fixed code on every test that does not
 * involve two sources, and would silently pass the one that does.
 *
 * <p>{@code set} clears {@code releasedOn}, as the {@code on conflict … do update} does: re-asserting
 * a hold that was released raises it again.
 */
public class InMemoryRetentionHolds implements RetentionHoldRepository {

    private record Key(UUID contactId, String reason, String sourceRef) {
    }

    private record Row(UUID workspaceId, LocalDate setOn, LocalDate releasedOn) {
    }

    private final Map<Key, Row> holds = new LinkedHashMap<>();

    @Override
    public void set(UUID workspaceId, UUID contactId, String reason, String sourceRef,
                    LocalDate setOn) {
        holds.put(new Key(contactId, reason, sourceRef), new Row(workspaceId, setOn, null));
    }

    @Override
    public void release(UUID workspaceId, UUID contactId, String reason, String sourceRef,
                        LocalDate releasedOn) {
        var key = new Key(contactId, reason, sourceRef);
        var row = holds.get(key);
        if (row == null || !row.workspaceId().equals(workspaceId)) {
            return;
        }
        holds.put(key, new Row(row.workspaceId(), row.setOn(), releasedOn));
    }

    @Override
    public List<String> activeReasons(UUID workspaceId, UUID contactId) {
        return holds.entrySet().stream()
            .filter(e -> e.getKey().contactId().equals(contactId))
            .filter(e -> e.getValue().workspaceId().equals(workspaceId))
            .filter(e -> e.getValue().releasedOn() == null)
            .map(e -> e.getKey().reason())
            .distinct()
            .sorted()
            .toList();
    }

    /** The join {@link InMemoryErasureDue} needs: is anything at all holding this contact? */
    boolean anyActiveFor(UUID workspaceId, UUID contactId) {
        return !activeReasons(workspaceId, contactId).isEmpty();
    }
}
