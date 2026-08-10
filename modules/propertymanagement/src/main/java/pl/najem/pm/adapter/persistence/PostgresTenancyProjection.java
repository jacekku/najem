package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.TenancyProjection;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.PartyRole;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** pm_tenancy and pm_tenancy_party. Every statement is scoped to the workspace as well as the id. */
@Repository
public class PostgresTenancyProjection implements TenancyProjection {

    private final JdbcTemplate jdbc;

    public PostgresTenancyProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void tenancyReserved(ReserveTenancy c) {
        var breakdown = c.monthly().breakdown();
        jdbc.update("insert into pm_tenancy(tenancy_id, workspace_id, unit_id, start_date, end_date, "
                + "legal_form, monthly_total, rent, admin_fee, media_advance, component_split, "
                + "rent_day, deposit_amount, payment_reference, state) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            c.tenancyId(), c.workspaceId(), c.unitId(), c.startDate(), c.term().endDate(),
            c.legalForm().name(), c.monthly().total(),
            breakdown == null ? null : breakdown.rent(),
            breakdown == null ? null : breakdown.adminFee(),
            breakdown == null ? null : breakdown.mediaAdvance(),
            c.monthly().componentSplitInContract(), c.rentDay(), c.depositAmount(),
            c.paymentReference(), Tenancy.State.RESERVED.name());

        party(c.tenancyId(), c.workspaceId(), c.tenantContactIds(), PartyRole.TENANT);
        party(c.tenancyId(), c.workspaceId(), c.guarantorContactIds(), PartyRole.GUARANTOR);
    }

    @Override
    public void tenantAdded(UUID tenancyId, UUID workspaceId, UUID contactId) {
        party(tenancyId, workspaceId, List.of(contactId), PartyRole.TENANT);
    }

    @Override
    public void tenantRemoved(UUID tenancyId, UUID workspaceId, UUID contactId) {
        jdbc.update("delete from pm_tenancy_party where tenancy_id = ? and contact_id = ? "
            + "and role = ? and workspace_id = ?",
            tenancyId, contactId, PartyRole.TENANT.name(), workspaceId);
    }

    /**
     * {@code do nothing} on conflict rather than an unguarded insert: {@code Tenancy.addTenant}
     * decides whether a contact is already on the tenancy, and a second event for one it refused
     * never reaches here — but a replay of a stream that legitimately contains the same contact
     * twice under two roles must not fail the write. The aggregate is where the rule lives; this
     * only has to be safe to run again.
     */
    private void party(UUID tenancyId, UUID workspaceId, List<UUID> contactIds, PartyRole role) {
        for (UUID contactId : contactIds) {
            jdbc.update("insert into pm_tenancy_party(tenancy_id, contact_id, role, workspace_id) "
                + "values (?,?,?,?) on conflict do nothing",
                tenancyId, contactId, role.name(), workspaceId);
        }
    }

    @Override
    public void reservationCancelled(UUID tenancyId, UUID workspaceId) {
        setState(Tenancy.State.CANCELLED, tenancyId, workspaceId);
    }

    /** The reason is stored because ERROR_ANNULLED is not an ending — see V20260810120100. */
    @Override
    public void ended(UUID tenancyId, UUID workspaceId, EndReason reason) {
        jdbc.update("update pm_tenancy set state = ?, end_reason = ? "
                + "where tenancy_id = ? and workspace_id = ?",
            Tenancy.State.ENDED.name(), reason.name(), tenancyId, workspaceId);
    }

    @Override
    public void activated(UUID tenancyId, UUID workspaceId, LocalDate on) {
        jdbc.update("update pm_tenancy set state = ?, activated_on = ? "
                + "where tenancy_id = ? and workspace_id = ?",
            Tenancy.State.ACTIVE.name(), on, tenancyId, workspaceId);
    }

    @Override
    public void rentChanged(UUID tenancyId, UUID workspaceId, MonthlyAmount monthly) {
        var breakdown = monthly.breakdown();
        jdbc.update("update pm_tenancy set monthly_total = ?, rent = ?, admin_fee = ?, "
                + "media_advance = ?, component_split = ? "
                + "where tenancy_id = ? and workspace_id = ?",
            monthly.total(),
            breakdown == null ? null : breakdown.rent(),
            breakdown == null ? null : breakdown.adminFee(),
            breakdown == null ? null : breakdown.mediaAdvance(),
            monthly.componentSplitInContract(), tenancyId, workspaceId);
    }

    @Override
    public void detailsCorrected(UUID tenancyId, UUID workspaceId, String paymentReference,
                                 Integer rentDay, LocalDate startDate, BigDecimal monthlyTotal) {
        jdbc.update("update pm_tenancy set payment_reference = ?, rent_day = ?, start_date = ?, "
                + "monthly_total = ? where tenancy_id = ? and workspace_id = ?",
            paymentReference, rentDay, startDate, monthlyTotal, tenancyId, workspaceId);
    }

    @Override
    public void insuranceExpirySet(UUID tenancyId, UUID workspaceId, LocalDate validTo) {
        jdbc.update("update pm_tenancy set insurance_valid_to = ? "
            + "where tenancy_id = ? and workspace_id = ?", validTo, tenancyId, workspaceId);
    }

    /** The state name is a literal from the enum, never caller input. */
    private void setState(Tenancy.State state, UUID tenancyId, UUID workspaceId) {
        jdbc.update("update pm_tenancy set state = ? where tenancy_id = ? and workspace_id = ?",
            state.name(), tenancyId, workspaceId);
    }
}
