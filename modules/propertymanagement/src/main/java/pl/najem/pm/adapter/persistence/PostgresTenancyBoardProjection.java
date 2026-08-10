package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyBoardRow;
import pl.najem.pm.application.TenancyDetailRow;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.PartyRole;
import pl.najem.pm.domain.Tenancy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The register over pm_tenancy, joined to the unit and property that name it and to the parties
 * table that says who is on it.
 *
 * <p><b>Two exclusions, and they are different facts.</b> A CANCELLED reservation was called off
 * before it let anything; an ERROR_ANNULLED ending says the tenancy should never have existed at
 * all. Both mean nobody lived there, and every other ending — expiry, notice, eviction — stays,
 * because those describe a tenancy that ran. {@code end_reason is null} keeps rows written before
 * V20260810120100 and every tenancy still running.
 *
 * <p>The tenant ids arrive as one correlated {@code array_agg} rather than a second round trip per
 * row. A join would have been the other option and was not taken: it multiplies the tenancy row by
 * its party count, and every caller would then have to collapse rows it did not ask to be given —
 * the fan-out moved rather than removed.
 */
@Repository
public class PostgresTenancyBoardProjection implements TenancyBoardProjection {

    private final JdbcTemplate jdbc;

    public PostgresTenancyBoardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The join and the two exclusions, shared by both reads verbatim.
     *
     * <p>One string rather than two copies, because the exclusions ARE this port's contract — a
     * register that hid a cancelled reservation while the single-tenancy read happily rendered one
     * would be two definitions of "this tenancy counts" over one table, which is exactly the defect
     * {@code UnitBoardQuery}'s javadoc records paying for. The two reads select different columns
     * and only this tail is shared; that is the part that could drift into a contradiction, and the
     * column lists are not.
     *
     * <p>The leading parameter is the workspace; each caller appends its own.
     */
    private static final String SCOPE = """
        from pm_tenancy t
        join pm_unit u on u.unit_id = t.unit_id
        join pm_property p on p.property_id = u.property_id
        where t.workspace_id = ?
          and t.state <> '%s'
          and (t.end_reason is null or t.end_reason <> '%s')
        """.formatted(Tenancy.State.CANCELLED.name(), EndReason.ERROR_ANNULLED.name());

    /**
     * The parties of one role, as one correlated {@code array_agg} rather than a second round trip.
     *
     * <p>A join would have been the other option and was not taken: it multiplies the tenancy row
     * by its party count, and every caller would then have to collapse rows it did not ask to be
     * given — the fan-out moved rather than removed. Interpolated rather than bound because it is
     * this class's own enum constant and never a caller's string; binding it would put the tenant
     * and guarantor sub-selects on different parameter positions in the two statements.
     */
    private static String parties(PartyRole role, String alias) {
        return """
            (select array_agg(pa.contact_id order by pa.contact_id)
               from pm_tenancy_party pa
              where pa.tenancy_id = t.tenancy_id
                and pa.workspace_id = t.workspace_id
                and pa.role = '%s') as %s
            """.formatted(role.name(), alias);
    }

    @Override
    public List<TenancyBoardRow> forWorkspace(UUID workspaceId) {
        return jdbc.query("""
            select t.tenancy_id, t.unit_id, u.name, p.address, t.state, t.start_date, t.end_date,
                   t.monthly_total,
            """ + parties(PartyRole.TENANT, "tenant_ids") + SCOPE
            + "order by t.start_date desc, u.name",
            (rs, n) -> new TenancyBoardRow(
                rs.getObject("tenancy_id", UUID.class),
                rs.getObject("unit_id", UUID.class),
                rs.getString("name"),
                rs.getString("address"),
                Tenancy.State.valueOf(rs.getString("state")),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getBigDecimal("monthly_total"),
                contactIds(rs, "tenant_ids")),
            workspaceId);
    }

    /**
     * The whole contract, and both party roles.
     *
     * <p>{@code rent}, {@code admin_fee} and {@code media_advance} are read straight through as
     * nullable rather than defaulted to zero: V22 records that "no split" and "a split with a zero
     * admin fee" are legally different, and a {@code getBigDecimal} coalesced here would erase the
     * distinction the {@code component_split} flag exists to carry.
     */
    @Override
    public Optional<TenancyDetailRow> forTenancy(UUID workspaceId, UUID tenancyId) {
        return jdbc.query("""
            select t.tenancy_id, t.unit_id, u.name, p.address, t.state, t.start_date, t.end_date,
                   t.legal_form, t.monthly_total, t.rent, t.admin_fee, t.media_advance,
                   t.component_split, t.rent_day, t.deposit_amount, t.payment_reference,
            """ + parties(PartyRole.TENANT, "tenant_ids") + ","
            + parties(PartyRole.GUARANTOR, "guarantor_ids") + SCOPE
            + "and t.tenancy_id = ?",
            (rs, n) -> new TenancyDetailRow(
                rs.getObject("tenancy_id", UUID.class),
                rs.getObject("unit_id", UUID.class),
                rs.getString("name"),
                rs.getString("address"),
                Tenancy.State.valueOf(rs.getString("state")),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                LegalForm.valueOf(rs.getString("legal_form")),
                rs.getBigDecimal("monthly_total"),
                rs.getBigDecimal("rent"),
                rs.getBigDecimal("admin_fee"),
                rs.getBigDecimal("media_advance"),
                rs.getBoolean("component_split"),
                rs.getInt("rent_day"),
                rs.getBigDecimal("deposit_amount"),
                rs.getString("payment_reference"),
                contactIds(rs, "tenant_ids"),
                contactIds(rs, "guarantor_ids")),
            workspaceId, tenancyId)
            .stream().findFirst();
    }

    /**
     * Null rather than an empty array is what {@code array_agg} returns when the subquery matches
     * nothing, and a tenancy reserved before V20260810120000 has no party rows at all. Empty list, not a
     * failure: the screen renders those as an unnamed tenant, which is true, where a throw here
     * would take the whole register down for one unbackfilled row.
     */
    private static List<UUID> contactIds(ResultSet rs, String column) throws SQLException {
        var array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        return List.of(Arrays.stream((Object[]) array.getArray())
            .map(value -> value instanceof UUID id ? id : UUID.fromString(value.toString()))
            .toArray(UUID[]::new));
    }
}
