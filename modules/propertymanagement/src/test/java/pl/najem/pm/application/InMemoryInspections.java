package pl.najem.pm.application;

import pl.najem.pm.domain.InspectionType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link InspectionProjection} and {@link OverdueInspectionQuery} over one list of rows.
 *
 * <p>One class implementing both because a fake stands in for the table, and pm_inspection is one
 * table. The ports stay separate where it matters — a service is handed the interface it needs, so
 * a writer still cannot read — and giving each its own map would mean an inspection recorded through
 * one was invisible to the other, which is a fake that cannot catch anything.
 *
 * <p>Addresses come from a second map because the real query joins pm_property. A fake that invented
 * an address would pass a test the join would fail, so an inspection on a property nobody registered
 * here comes back with a null address exactly as an outer join would — and the inner join in
 * {@code PostgresOverdueInspectionQuery} would drop the row entirely. That difference is the one
 * thing this double cannot express; {@code ComplianceServiceTest} is what covers it.
 *
 * <p>The overdue rule is reimplemented from the mechanism, not copied from the outcome: filter to
 * the workspace, keep only the latest performed_on per (property, type), then take those past the
 * date. Deriving it from what the SQL returns is how a fake starts agreeing with a bug.
 */
public class InMemoryInspections implements InspectionProjection, OverdueInspectionQuery {

    public record Row(UUID inspectionId, UUID workspaceId, UUID propertyId, InspectionType type,
                      LocalDate performedOn, LocalDate nextDueOn, String reportDoc,
                      String findings) {}

    private record Latest(UUID propertyId, InspectionType type) {}

    private final List<Row> rows = new ArrayList<>();
    private final Map<UUID, String> addresses = new LinkedHashMap<>();

    /** Stands in for the pm_property row the real query joins to. */
    public void addressOf(UUID propertyId, String address) {
        addresses.put(propertyId, address);
    }

    @Override
    public void inspectionRecorded(UUID inspectionId, UUID workspaceId, UUID propertyId,
                                   InspectionType type, LocalDate performedOn, LocalDate nextDueOn,
                                   String reportDoc, String findings) {
        if (rows.stream().anyMatch(row -> row.inspectionId().equals(inspectionId))) {
            throw new IllegalStateException("duplicate key on pm_inspection: " + inspectionId);
        }
        rows.add(new Row(inspectionId, workspaceId, propertyId, type, performedOn, nextDueOn,
            reportDoc, findings));
    }

    @Override
    public List<OverdueInspection> overdue(UUID workspaceId, LocalDate on) {
        var latest = new LinkedHashMap<Latest, Row>();
        for (Row row : rows) {
            if (!row.workspaceId().equals(workspaceId)) {
                continue;
            }
            latest.merge(new Latest(row.propertyId(), row.type()), row,
                (a, b) -> b.performedOn().isAfter(a.performedOn()) ? b : a);
        }
        return latest.values().stream()
            .filter(row -> row.nextDueOn().isBefore(on))
            .sorted(Comparator.comparing(Row::nextDueOn))
            .map(row -> new OverdueInspection(row.propertyId(), addresses.get(row.propertyId()),
                row.type(), row.performedOn(), row.nextDueOn()))
            .toList();
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }
}
