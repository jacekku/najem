package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.util.UUID;

/**
 * Checks that the caller's workspace actually owns the thing they are about to change.
 *
 * <p>This sits at the adapter boundary because that is the only place the CALLER's identity
 * exists — a service given only an aggregate id cannot tell whose request it is serving. The
 * {@code and workspace_id = ?} predicates on the projection writes are defence in depth behind
 * it, not a substitute: the predicate stops the wrong ROW being written, the guard stops the
 * wrong CALLER writing at all.
 *
 * <p>Fail closed per rule 7(2): a subject that does not exist and a subject in another agency
 * are the same answer — not found. Distinguishing them would confirm that someone else's
 * property id is real, which is a disclosure in itself.
 */
@Component
public class WorkspaceGuard {

    private final JdbcTemplate jdbc;

    public WorkspaceGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireProperty(UUID caller, UUID propertyId) {
        require(caller, "pm_property", "property_id", propertyId);
    }

    public void requireUnit(UUID caller, UUID unitId) {
        require(caller, "pm_unit", "unit_id", unitId);
    }

    public void requireTenancy(UUID caller, UUID tenancyId) {
        require(caller, "pm_tenancy", "tenancy_id", tenancyId);
    }

    public void requireRepair(UUID caller, UUID repairId) {
        require(caller, "pm_repair", "repair_id", repairId);
    }

    /**
     * The table and column are literals chosen in this class, never caller input. The query asks
     * "does a row with this id exist IN THIS WORKSPACE" rather than fetching the workspace and
     * comparing — so a null caller can never accidentally equal a null column.
     */
    private void require(UUID caller, String table, String idColumn, UUID id) {
        if (caller == null || id == null) {
            throw new UnknownInThisWorkspaceException(table + " " + id);
        }
        Integer found = jdbc.queryForObject(
            "select count(*) from " + table + " where " + idColumn + " = ? and workspace_id = ?",
            Integer.class, id, caller);
        if (found == null || found == 0) {
            throw new UnknownInThisWorkspaceException(table + " " + id);
        }
    }
}
