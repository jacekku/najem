package pl.najem.pm.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;

/**
 * What needs a manager's attention. Read-only, and every query filters on workspace_id.
 *
 * <p>Deliberately NOT the unit board's occupancy: that is Reporting's, built from PM's own event
 * streams under the seq 65 allowlist. Building a second projection of the same events here would
 * give two answers to "is this unit occupied" with no way to tell which drifted.
 */
@Component
public class AttentionListsQuery {

    /**
     * One month, shared by every deadline here rather than configured per list. A second lead
     * time is a second thing to explain to a manager, and no domain rule asks for one.
     */
    private static final Period WINDOW = Period.ofMonths(1);

    public record TenancyAttentionRow(UUID tenancyId, UUID unitId, String unitName,
                                      String propertyAddress, LocalDate on) {
    }

    private final JdbcTemplate jdbc;
    private final OpenRepairQuery repairs;

    public AttentionListsQuery(JdbcTemplate jdbc, OpenRepairQuery repairs) {
        this.jdbc = jdbc;
        this.repairs = repairs;
    }

    /** Reserved tenancies whose start date is within the window — get the keys ready. */
    public List<TenancyAttentionRow> startingSoon(UUID workspaceId, LocalDate on) {
        return tenancyRows("t.start_date", "t.state = 'RESERVED' and t.start_date is not null",
            workspaceId, on);
    }

    /** Active tenancies whose end is within the window — renew, or start the end-of-tenancy work. */
    public List<TenancyAttentionRow> endingSoon(UUID workspaceId, LocalDate on) {
        return tenancyRows("t.end_date", "t.state = 'ACTIVE' and t.end_date is not null",
            workspaceId, on);
    }

    /**
     * Tenant OC policies lapsing within the window (v1.1 amendment). Same window as ending-soon
     * on purpose. A tenancy with no policy at all does NOT appear here — that is a different
     * problem (never insured, not about to lapse) and merging the two would hide both.
     */
    public List<TenancyAttentionRow> insuranceExpiring(UUID workspaceId, LocalDate on) {
        return tenancyRows("t.insurance_valid_to",
            "t.state = 'ACTIVE' and t.insurance_valid_to is not null", workspaceId, on);
    }

    /**
     * Delegated to {@link OpenRepairQuery} rather than queried here: pm_repair is written through
     * {@link RepairProjection}, and a table written through a port and read around it has two
     * definitions of its own shape. The tenancy lists below are still raw — they go when this class
     * gets its own turn.
     */
    public List<OpenRepair> openRepairs(UUID workspaceId) {
        return repairs.openRepairs(workspaceId);
    }

    /**
     * The three tenancy lists differ only in which date they watch and which state qualifies, so
     * they share one query rather than three that can drift apart. dateColumn and extraWhere are
     * fixed strings chosen in this class — never caller input.
     */
    private List<TenancyAttentionRow> tenancyRows(String dateColumn, String extraWhere,
                                                  UUID workspaceId, LocalDate on) {
        return jdbc.query("""
            select t.tenancy_id, t.unit_id, u.name, p.address, %s
            from pm_tenancy t
            join pm_unit u on u.unit_id = t.unit_id
            join pm_property p on p.property_id = u.property_id
            where t.workspace_id = ? and %s and %s <= ?
            order by %s
            """.formatted(dateColumn, extraWhere, dateColumn, dateColumn),
            (rs, n) -> new TenancyAttentionRow(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class)),
            workspaceId, on.plus(WINDOW));
    }
}
