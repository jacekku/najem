package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.OverdueInspection;
import pl.najem.pm.application.OverdueInspectionQuery;
import pl.najem.pm.domain.InspectionType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@link OverdueInspectionQuery} over pm_inspection joined to pm_property for the address.
 *
 * <p>The correlated subquery is what keeps a superseded inspection off the list, and it is scoped
 * by workspace as well as property and type. Dropping that predicate would still return the right
 * rows in practice — a property belongs to one agency — but it would make the subquery scan another
 * agency's rows to decide this one's answer, and "correct because the data happens not to overlap"
 * is not a boundary.
 */
@Repository
public class PostgresOverdueInspectionQuery implements OverdueInspectionQuery {

    private final JdbcTemplate jdbc;

    public PostgresOverdueInspectionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
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
