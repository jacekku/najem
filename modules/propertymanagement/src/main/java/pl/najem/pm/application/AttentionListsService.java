package pl.najem.pm.application;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.UUID;

/**
 * What needs a manager's attention. Read-only, and every list is scoped to one workspace.
 *
 * <p>Deliberately NOT the unit board's occupancy: that is Reporting's, built from PM's own event
 * streams under the seq 65 allowlist. Building a second projection of the same events here would
 * give two answers to "is this unit occupied" with no way to tell which drifted.
 *
 * <p>This was {@code AttentionListsQuery}, a class holding a {@code JdbcTemplate} and the lead-time
 * policy together. The SQL is now {@link AttentionListsProjection}; what stays here is the one
 * decision that must not vary with the store — how far ahead "soon" reaches.
 */
@Service
public class AttentionListsService {

    /**
     * One month, shared by every deadline here rather than configured per list. A second lead time
     * is a second thing to explain to a manager, and no domain rule asks for one. Rule 10: this is
     * a policy, so it lives in the layer that owns the decision and never in the query.
     */
    private static final Period WINDOW = Period.ofMonths(1);

    private final AttentionListsProjection tenancies;
    private final OpenRepairQuery repairs;

    public AttentionListsService(AttentionListsProjection tenancies, OpenRepairQuery repairs) {
        this.tenancies = tenancies;
        this.repairs = repairs;
    }

    /** Reserved tenancies whose start date is within the window — get the keys ready. */
    public List<TenancyAttentionRow> startingSoon(UUID workspaceId, LocalDate on) {
        return tenancies.startingSoon(workspaceId, on.plus(WINDOW));
    }

    /** Active tenancies whose end is within the window — renew, or start the end-of-tenancy work. */
    public List<TenancyAttentionRow> endingSoon(UUID workspaceId, LocalDate on) {
        return tenancies.endingSoon(workspaceId, on.plus(WINDOW));
    }

    /**
     * Tenant OC policies lapsing within the window (v1.1 amendment). Same window as ending-soon on
     * purpose.
     */
    public List<TenancyAttentionRow> insuranceExpiring(UUID workspaceId, LocalDate on) {
        return tenancies.insuranceExpiring(workspaceId, on.plus(WINDOW));
    }

    /** Open repairs are {@link OpenRepairQuery}'s: pm_repair has one definition of its own shape. */
    public List<OpenRepair> openRepairs(UUID workspaceId) {
        return repairs.openRepairs(workspaceId);
    }
}
