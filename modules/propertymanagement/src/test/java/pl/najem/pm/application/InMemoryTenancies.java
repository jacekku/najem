package pl.najem.pm.application;

import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * {@link TenancyProjection} and {@link AttentionListsProjection} over one map of rows, as pm_tenancy
 * is one table. The ports stay separate where it counts — a service is handed only the write one.
 *
 * <p>Two mechanisms are copied rather than their outcomes (rule 14):
 *
 * <ul>
 *   <li>Every update carries {@code and workspace_id = ?}, and a mismatch matches no row, changes
 *       nothing, and raises nothing. So these are silent no-ops rather than throws — a fake that
 *       threw would pass a test the database fails silently, which is what the predicate is for.
 *   <li>The attention lists are INNER joins onto pm_unit and pm_property. A tenancy whose unit row
 *       is missing does not appear on any list. That is why this takes the portfolio's rows instead
 *       of carrying a unit name of its own: copying the name here would make the join unfailable.
 * </ul>
 */
public class InMemoryTenancies implements TenancyProjection, AttentionListsProjection {

    public record Row(UUID tenancyId, UUID workspaceId, UUID unitId, LocalDate startDate,
                      LocalDate endDate, BigDecimal monthlyTotal, MonthlyAmount.Breakdown breakdown,
                      boolean componentSplit, Integer rentDay, BigDecimal depositAmount,
                      String paymentReference, Tenancy.State state, LocalDate activatedOn,
                      LocalDate insuranceValidTo) {}

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private final InMemoryPortfolioProjection portfolio;

    public InMemoryTenancies(InMemoryPortfolioProjection portfolio) {
        this.portfolio = portfolio;
    }

    @Override
    public void tenancyReserved(ReserveTenancy c) {
        var row = new Row(c.tenancyId(), c.workspaceId(), c.unitId(), c.startDate(),
            c.term().endDate(), c.monthly().total(), c.monthly().breakdown(),
            c.monthly().componentSplitInContract(), c.rentDay(), c.depositAmount(),
            c.paymentReference(), Tenancy.State.RESERVED, null, null);
        if (rows.putIfAbsent(c.tenancyId(), row) != null) {
            throw new IllegalStateException("duplicate key on pm_tenancy: " + c.tenancyId());
        }
    }

    @Override
    public void reservationCancelled(UUID tenancyId, UUID workspaceId) {
        update(tenancyId, workspaceId, row -> withState(row, Tenancy.State.CANCELLED));
    }

    @Override
    public void ended(UUID tenancyId, UUID workspaceId) {
        update(tenancyId, workspaceId, row -> withState(row, Tenancy.State.ENDED));
    }

    @Override
    public void activated(UUID tenancyId, UUID workspaceId, LocalDate on) {
        update(tenancyId, workspaceId, row -> new Row(row.tenancyId(), row.workspaceId(),
            row.unitId(), row.startDate(), row.endDate(), row.monthlyTotal(), row.breakdown(),
            row.componentSplit(), row.rentDay(), row.depositAmount(), row.paymentReference(),
            Tenancy.State.ACTIVE, on, row.insuranceValidTo()));
    }

    @Override
    public void rentChanged(UUID tenancyId, UUID workspaceId, MonthlyAmount monthly) {
        update(tenancyId, workspaceId, row -> new Row(row.tenancyId(), row.workspaceId(),
            row.unitId(), row.startDate(), row.endDate(), monthly.total(), monthly.breakdown(),
            monthly.componentSplitInContract(), row.rentDay(), row.depositAmount(),
            row.paymentReference(), row.state(), row.activatedOn(), row.insuranceValidTo()));
    }

    @Override
    public void detailsCorrected(UUID tenancyId, UUID workspaceId, String paymentReference,
                                 Integer rentDay, LocalDate startDate, BigDecimal monthlyTotal) {
        update(tenancyId, workspaceId, row -> new Row(row.tenancyId(), row.workspaceId(),
            row.unitId(), startDate, row.endDate(), monthlyTotal, row.breakdown(),
            row.componentSplit(), rentDay, row.depositAmount(), paymentReference, row.state(),
            row.activatedOn(), row.insuranceValidTo()));
    }

    @Override
    public void insuranceExpirySet(UUID tenancyId, UUID workspaceId, LocalDate validTo) {
        update(tenancyId, workspaceId, row -> new Row(row.tenancyId(), row.workspaceId(),
            row.unitId(), row.startDate(), row.endDate(), row.monthlyTotal(), row.breakdown(),
            row.componentSplit(), row.rentDay(), row.depositAmount(), row.paymentReference(),
            row.state(), row.activatedOn(), validTo));
    }

    @Override
    public List<TenancyAttentionRow> startingSoon(UUID workspaceId, LocalDate through) {
        return list(workspaceId, through, row -> row.state() == Tenancy.State.RESERVED,
            Row::startDate);
    }

    @Override
    public List<TenancyAttentionRow> endingSoon(UUID workspaceId, LocalDate through) {
        return list(workspaceId, through, row -> row.state() == Tenancy.State.ACTIVE, Row::endDate);
    }

    @Override
    public List<TenancyAttentionRow> insuranceExpiring(UUID workspaceId, LocalDate through) {
        return list(workspaceId, through, row -> row.state() == Tenancy.State.ACTIVE,
            Row::insuranceValidTo);
    }

    /**
     * The shared shape of the three lists: in this workspace, in this state, with the watched date
     * set and not after {@code through}, joined to a unit and a property that exist, earliest first.
     */
    private List<TenancyAttentionRow> list(UUID workspaceId, LocalDate through,
                                           Predicate<Row> state,
                                           java.util.function.Function<Row, LocalDate> watched) {
        return rows.values().stream()
            .filter(row -> row.workspaceId().equals(workspaceId))
            .filter(state)
            .filter(row -> watched.apply(row) != null && !watched.apply(row).isAfter(through))
            .sorted(Comparator.comparing(watched))
            .flatMap(row -> portfolio.unit(row.unitId()).stream()
                .flatMap(unit -> portfolio.property(unit.propertyId()).stream()
                    .map(property -> new TenancyAttentionRow(row.tenancyId(), row.unitId(),
                        unit.name(), property.address(), watched.apply(row)))))
            .toList();
    }

    private static Row withState(Row row, Tenancy.State state) {
        return new Row(row.tenancyId(), row.workspaceId(), row.unitId(), row.startDate(),
            row.endDate(), row.monthlyTotal(), row.breakdown(), row.componentSplit(), row.rentDay(),
            row.depositAmount(), row.paymentReference(), state, row.activatedOn(),
            row.insuranceValidTo());
    }

    /** {@code where tenancy_id = ? and workspace_id = ?}: no match changes nothing, silently. */
    private void update(UUID tenancyId, UUID workspaceId, UnaryOperator<Row> change) {
        var row = rows.get(tenancyId);
        if (row == null || !row.workspaceId().equals(workspaceId)) {
            return;
        }
        rows.put(tenancyId, change.apply(row));
    }

    /** Reading a row is a test affordance, deliberately not on the write port. */
    public Optional<Row> tenancy(UUID tenancyId) {
        return Optional.ofNullable(rows.get(tenancyId));
    }
}
