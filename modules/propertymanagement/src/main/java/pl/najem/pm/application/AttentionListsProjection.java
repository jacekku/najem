package pl.najem.pm.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The read side of pm_tenancy: the three deadline lists a manager's attention screen is built from.
 *
 * <p>Two ports over one derived table is not duplication (rule A7). {@link TenancyProjection}
 * writes it and is what services hold; this reads it and is what a screen holds. One port carrying
 * both would put a read of the projection back within reach of every service that writes it, which
 * is the failure the split exists to prevent.
 *
 * <p>Each method takes {@code through} — the last date that still counts — rather than "today" and
 * a window. The window is a policy about how far ahead a manager wants to look, and rule 10 puts
 * policy in the layer that owns the decision: {@link AttentionListsService} chooses it, and it must
 * not vary with the store. Every method filters on the workspace.
 */
public interface AttentionListsProjection {

    /** Reserved tenancies starting on or before {@code through}, earliest first. */
    List<TenancyAttentionRow> startingSoon(UUID workspaceId, LocalDate through);

    /** Active tenancies ending on or before {@code through}, earliest first. */
    List<TenancyAttentionRow> endingSoon(UUID workspaceId, LocalDate through);

    /**
     * Active tenancies whose tenant OC policy lapses on or before {@code through}, earliest first.
     * A tenancy with no policy at all does NOT appear: that is a different problem — never insured,
     * rather than about to lapse — and merging the two would hide both.
     */
    List<TenancyAttentionRow> insuranceExpiring(UUID workspaceId, LocalDate through);
}
