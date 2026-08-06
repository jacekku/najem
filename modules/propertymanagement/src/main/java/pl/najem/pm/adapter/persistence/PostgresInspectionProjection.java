package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.InspectionProjection;
import pl.najem.pm.domain.InspectionType;

import java.time.LocalDate;
import java.util.UUID;

/** {@link InspectionProjection} over pm_inspection. */
@Repository
public class PostgresInspectionProjection implements InspectionProjection {

    private final JdbcTemplate jdbc;

    public PostgresInspectionProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inspectionRecorded(UUID inspectionId, UUID workspaceId, UUID propertyId,
                                   InspectionType type, LocalDate performedOn, LocalDate nextDueOn,
                                   String reportDoc, String findings) {
        jdbc.update("insert into pm_inspection(inspection_id, workspace_id, property_id, type, "
                + "performed_on, next_due_on, report_doc, findings) values (?,?,?,?,?,?,?,?)",
            inspectionId, workspaceId, propertyId, type.name(), performedOn, nextDueOn,
            reportDoc, findings);
    }
}
