package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.util.UUID;

/**
 * Checks that the caller's workspace actually owns the thing they are about to change, by looking
 * for a row with that id in that workspace.
 *
 * <p><b>Being migrated away from, and here is the argument it lost.</b> This used to say it sat at
 * the adapter boundary because that is the only place the CALLER's identity exists — a service
 * given only an aggregate id cannot tell whose request it is serving. True, and it stopped one step
 * short: a service can be *handed* the caller. Once it is, the check moves next to the decision and
 * covers every route in, not just the one that goes through a controller.
 *
 * <p>The second reason is the one that mattered more. This asks the question of {@code pm_property}
 * and {@code pm_unit}, which are projections — written by a second statement after the append, and
 * therefore allowed to lag the streams they are derived from. {@code PortfolioService} then rebuilt
 * the aggregate and read the same workspace off the record itself, so one question had two answers
 * from two stores. {@code Unit.requireOwnedBy} and {@code Property.requireOwnedBy} are that
 * question asked once, of the record.
 *
 * <p>Still used by the tenancy, checklist, compliance, attention and repair endpoints, which have
 * not been moved yet. {@code WorkspaceBoundaryTest.everyWriteMappingChecksTheWorkspaceOrHandsItOn}
 * accepts either form and fails on neither, and its companion fails once no caller is left here —
 * which is the signal to delete this class rather than to widen anything.
 *
 * <p>Fail closed per rule 7(2): a subject that does not exist and a subject in another agency
 * are the same answer — not found. Distinguishing them would confirm that someone else's
 * property id is real, which is a disclosure in itself. The domain checks carry the same rule.
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

    /*
     * requireRepair is gone. Repairs are the one subject this class was written for -- the concrete
     * hole was POST /repairs/{id}/complete finishing a repair in any agency -- and the defence has
     * moved to Repair.requireOwnedBy, asked by RepairService for every caller rather than by one
     * handler. Leaving the method here with no production caller would have kept a second, weaker
     * answer to the same question available to whoever found it first.
     */

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
