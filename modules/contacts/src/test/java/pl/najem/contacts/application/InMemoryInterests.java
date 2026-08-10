package pl.najem.contacts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The interest table in a map.
 *
 * <p>{@link #find} filters on the workspace because that filter <em>is</em> the gate on every
 * command on an interest — it is the only lookup in the module not fronted by
 * {@link ContactDirectory#requireIn}, so a double that answered without it would make those
 * commands appear guarded when they were not.
 *
 * <p>{@code status} is a stored string rather than a derived flag, as in the table, so a test can
 * tell a withdrawn interest from an absent one.
 */
public class InMemoryInterests implements InterestRepository {

    private record Row(UUID workspaceId, UUID contactId, UUID unitId, BigDecimal willingToPay,
                       LocalDate desiredStart, String status, UUID convertedToTenancyId) {
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    @Override
    public void insert(UUID interestId, UUID workspaceId, UUID contactId, UUID unitId,
                       BigDecimal willingToPay, LocalDate desiredStart) {
        rows.put(interestId, new Row(workspaceId, contactId, unitId, willingToPay, desiredStart, "active", null));
    }

    @Override
    public Optional<Interest> find(UUID workspaceId, UUID interestId) {
        return mine(workspaceId, interestId)
            .map(row -> new Interest(interestId, row.contactId(), row.unitId(),
                row.willingToPay(), row.desiredStart(), row.status()));
    }

    private Optional<Row> mine(UUID workspaceId, UUID interestId) {
        return Optional.ofNullable(rows.get(interestId))
            .filter(row -> row.workspaceId().equals(workspaceId));
    }

    @Override
    public void withdraw(UUID workspaceId, UUID interestId) {
        mine(workspaceId, interestId).ifPresent(row ->
            rows.put(interestId, new Row(row.workspaceId(), row.contactId(), row.unitId(),
                row.willingToPay(), row.desiredStart(), "withdrawn", row.convertedToTenancyId())));
    }

    @Override
    public void convert(UUID workspaceId, UUID interestId, UUID tenancyId) {
        mine(workspaceId, interestId).ifPresent(row ->
            rows.put(interestId, new Row(row.workspaceId(), row.contactId(), row.unitId(),
                row.willingToPay(), row.desiredStart(), "converted", tenancyId)));
    }

    @Override
    public List<Interest> activeForUnit(UUID workspaceId, UUID unitId) {
        return rows.entrySet().stream()
            .filter(e -> e.getValue().workspaceId().equals(workspaceId))
            .filter(e -> e.getValue().unitId().equals(unitId))
            .filter(e -> e.getValue().status().equals("active"))
            .sorted(Map.Entry.comparingByKey())
            .map(e -> new Interest(e.getKey(), e.getValue().contactId(), e.getValue().unitId(),
                e.getValue().willingToPay(), e.getValue().desiredStart(), e.getValue().status()))
            .toList();
    }

    @Override
    public void deleteAllFor(UUID workspaceId, UUID contactId) {
        rows.entrySet().removeIf(e -> e.getValue().workspaceId().equals(workspaceId)
            && e.getValue().contactId().equals(contactId));
    }
}
