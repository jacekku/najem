package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.InspectionType;
import pl.najem.pm.domain.Property;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Statutory inspections and what is overdue. Every query filters on workspace_id. */
@Service
@Transactional
public class ComplianceService {

    /** An inspection whose deadline has passed, for the manager's attention list. */
    public record OverdueInspection(UUID propertyId, String address, InspectionType type,
                                    LocalDate performedOn, LocalDate nextDueOn) {
    }

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public ComplianceService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID recordInspection(UUID propertyId, InspectionType type, LocalDate performedOn,
                                 String reportDoc, String findings) {
        var stream = store.load(propertyId, "Property");
        var property = Property.from(stream.events());
        var events = property.recordInspection(type, performedOn, reportDoc, findings);
        store.append(propertyId, "Property", stream.version(), events, List.of());

        UUID inspectionId = UUID.randomUUID();
        jdbc.update("insert into pm_inspection(inspection_id, workspace_id, property_id, type, "
                + "performed_on, next_due_on, report_doc, findings) values (?,?,?,?,?,?,?,?)",
            inspectionId, property.workspaceId(), propertyId, type.name(), performedOn,
            type.nextDue(performedOn), reportDoc, findings);
        return inspectionId;
    }

    /**
     * Overdue as of a date, newest deadline first. Only the LATEST inspection of each type counts
     * — an annual gas check done in 2026 and again in 2027 is one obligation, not two, and
     * reporting the superseded 2026 row as overdue would be a false alarm on a compliant property.
     */
    public List<OverdueInspection> overdue(UUID workspaceId, LocalDate on) {
        return jdbc.query("""
            select i.property_id, p.address, i.type, i.performed_on, i.next_due_on
            from pm_inspection i
            join pm_property p on p.property_id = i.property_id
            where i.workspace_id = ?
              and i.next_due_on < ?
              and i.performed_on = (
                    select max(l.performed_on) from pm_inspection l
                    where l.property_id = i.property_id and l.type = i.type
                        and l.workspace_id = i.workspace_id)
            order by i.next_due_on
            """,
            (rs, n) -> new OverdueInspection(rs.getObject(1, UUID.class), rs.getString(2),
                InspectionType.valueOf(rs.getString(3)), rs.getObject(4, LocalDate.class),
                rs.getObject(5, LocalDate.class)),
            workspaceId, on);
    }
}
