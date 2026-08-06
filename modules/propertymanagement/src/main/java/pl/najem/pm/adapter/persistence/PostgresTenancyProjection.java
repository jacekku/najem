package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.TenancyProjection;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** pm_tenancy. Every statement is scoped to the workspace as well as the id. */
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
    }

    @Override
    public void reservationCancelled(UUID tenancyId, UUID workspaceId) {
        setState(Tenancy.State.CANCELLED, tenancyId, workspaceId);
    }

    @Override
    public void ended(UUID tenancyId, UUID workspaceId) {
        setState(Tenancy.State.ENDED, tenancyId, workspaceId);
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
