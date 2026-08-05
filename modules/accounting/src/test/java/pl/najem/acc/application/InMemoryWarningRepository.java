package pl.najem.acc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A {@link WarningRepository} kept in a list.
 *
 * <p>Insertion order is the order they come back, which is what {@code order by raised_at} gives
 * for warnings raised in one transaction — the only ordering any caller depends on.
 *
 * <p>{@code seen} is stored and filtered on read, as the SQL does, rather than the entry being
 * removed. A fake that dropped the row would agree with every assertion about {@code unseen} and
 * still hide the difference between "marked seen" and "never happened".
 */
public class InMemoryWarningRepository implements WarningRepository {

    private record Entry(UUID workspaceId, Warning warning, boolean seen) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /** Every warning ever raised in that workspace, seen or not. */
    public List<Warning> all(UUID workspaceId) {
        return entries.stream()
            .filter(entry -> entry.workspaceId().equals(workspaceId))
            .map(Entry::warning)
            .toList();
    }

    @Override
    public void raise(UUID workspaceId, UUID tenancyId, List<WarningToRaise> raised) {
        for (WarningToRaise warning : raised) {
            entries.add(new Entry(workspaceId,
                new Warning(UUID.randomUUID(), tenancyId, warning.kind(), warning.detail()), false));
        }
    }

    @Override
    public List<Warning> unseen(UUID workspaceId) {
        return entries.stream()
            .filter(entry -> entry.workspaceId().equals(workspaceId))
            .filter(entry -> !entry.seen())
            .map(Entry::warning)
            .toList();
    }

    /** Workspace-scoped, so another agency's warning is not silenced — as the update's clause is. */
    @Override
    public void markSeen(UUID workspaceId, UUID warningId) {
        entries.replaceAll(entry ->
            entry.workspaceId().equals(workspaceId) && entry.warning().warningId().equals(warningId)
                ? new Entry(entry.workspaceId(), entry.warning(), true)
                : entry);
    }
}
