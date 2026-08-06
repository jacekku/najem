package pl.najem.pm.application;

import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Write-only onto pm_tenancy, which is derived from the Tenancy streams and could be dropped and
 * rebuilt from them. Rule A7: the port over a derived store is a {@code Projection}, and it may not
 * be read to decide anything — {@link AttentionListsProjection} is the read side, held by a screen.
 *
 * <p>Write-only is the whole point here. {@code WorkspaceGuard} used to ask this table who owned a
 * tenancy, and {@link TenancyService} then rebuilt the aggregate and read the same fact off the
 * record. One question, two answers, from two stores a second statement is allowed to leave
 * disagreeing. With no read on this interface there is nowhere for the second answer to come from.
 *
 * <p>Every method still carries the workspace, because every statement behind it is scoped
 * {@code where tenancy_id = ? and workspace_id = ?}. That is belt-and-braces now that the aggregate
 * has already refused a foreign caller, and it stays: a projection write that could touch another
 * agency's row is worth making impossible twice.
 */
public interface TenancyProjection {

    void tenancyReserved(ReserveTenancy command);

    void reservationCancelled(UUID tenancyId, UUID workspaceId);

    void activated(UUID tenancyId, UUID workspaceId, LocalDate on);

    void ended(UUID tenancyId, UUID workspaceId);

    /** The applied rent, breakdown included — see {@code TenancyService.applyRentChange}. */
    void rentChanged(UUID tenancyId, UUID workspaceId, MonthlyAmount monthly);

    void detailsCorrected(UUID tenancyId, UUID workspaceId, String paymentReference,
                          Integer rentDay, LocalDate startDate, BigDecimal monthlyTotal);

    /** Only ever the aggregate's answer to "which policy is current" — never recomputed here. */
    void insuranceExpirySet(UUID tenancyId, UUID workspaceId, LocalDate validTo);
}
