package pl.najem.pm.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every tenancy in a workspace, with enough of its unit and parties to be listed.
 *
 * <p>The read side of pm_tenancy, beside {@link AttentionListsProjection} rather than folded into
 * it. Those are three deadline lists — "what needs doing in the next N days"; this is the register —
 * "everything the agency has let". A screen asking the second question through a port built for the
 * first would be passing a {@code through} date it does not mean.
 *
 * <p><b>Not on {@link TenancyProjection}</b>, which stays write-only for the reason its own javadoc
 * gives: a service that can read this table has a second answer available to a question the
 * aggregate already answers, and that is what retired {@code WorkspaceGuard}. Two ports over one
 * derived table is not duplication (rule A7) — the split is what keeps the read out of reach of
 * every service that writes.
 *
 * <p>One query, not a per-tenancy fan-out. {@link UnitBoardQuery}'s javadoc makes the argument at
 * length and it applies unchanged: a board renders N rows at once, so a primitive that answers for
 * one row pushes an N+1 into whoever renders it, where nobody owns it.
 */
public interface TenancyBoardProjection {

    /**
     * Every tenancy in the workspace, most recently started first.
     *
     * <p>Cancelled reservations are absent. A tenancy that was called off never let the unit and is
     * not part of the register — {@code reporting_unit_period}'s own V64 migration draws the same
     * line between annulled and ended, for the same reason. Ended tenancies DO appear: they ran,
     * and a register that forgot them would tell a manager the unit had never been let.
     */
    List<TenancyBoardRow> forWorkspace(UUID workspaceId);

    /**
     * One tenancy, for the screen that shows one tenancy.
     *
     * <p>The warning above is about a board calling a per-row primitive N times, and it stands.
     * This is the other case, and {@code UnitBoardQuery.forUnit} spells out the same distinction:
     * a single-subject screen calling it once. Reaching for {@link #forWorkspace} and filtering
     * would read an agency's worth of tenancies to render one of them, which is the fan-out this
     * port's javadoc refuses, only paid on the wrong axis.
     *
     * <p>Empty for unknown, foreign, cancelled and error-annulled alike — the same four answers
     * {@link #forWorkspace} collapses by omitting the row. A caller that could tell "not yours"
     * from "no such tenancy" would have an oracle for ids in other agencies, which is why every
     * read here is undifferentiated.
     */
    Optional<TenancyDetailRow> forTenancy(UUID workspaceId, UUID tenancyId);
}
