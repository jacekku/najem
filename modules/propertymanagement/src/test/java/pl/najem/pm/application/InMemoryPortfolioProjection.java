package pl.najem.pm.application;

import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PortfolioProjection} in two maps.
 *
 * <p>The thing it has to model is the predicate, not the result. Every update in
 * {@code PostgresPortfolioProjection} carries {@code and workspace_id = ?}, and what that does when
 * the workspace does not match is <em>nothing</em> — no row matches, the statement reports zero
 * rows changed, and no exception is raised. So the updates here are no-ops on a workspace mismatch
 * rather than throwing, because a fake that threw would pass a test the database would fail
 * silently, which is the failure mode the predicate exists to prevent.
 *
 * <p>Inserts are the other half: {@code pm_property} and {@code pm_unit} key on the id, so writing
 * the same id twice is a primary-key violation. This raises rather than overwriting, or a test
 * could double-create and see a plausible single row.
 *
 * <p>Reading is a test affordance, deliberately not on the port — see
 * {@link PortfolioProjection} for why this store must not be readable from a service.
 */
public class InMemoryPortfolioProjection implements PortfolioProjection {

    public record PropertyRow(UUID propertyId, UUID workspaceId, String address) {}

    public record UnitRow(UUID unitId, UUID propertyId, UUID workspaceId, String name,
                          BigDecimal baseRent, Unit.MarketState marketState, String listingRef) {}

    private final Map<UUID, PropertyRow> properties = new LinkedHashMap<>();
    private final Map<UUID, UnitRow> units = new LinkedHashMap<>();

    @Override
    public void propertyCreated(UUID propertyId, UUID workspaceId, String address) {
        if (properties.putIfAbsent(propertyId,
                new PropertyRow(propertyId, workspaceId, address)) != null) {
            throw new IllegalStateException("duplicate key on pm_property: " + propertyId);
        }
    }

    @Override
    public void unitAdded(UUID unitId, UUID propertyId, UUID workspaceId, String name,
                          BigDecimal baseRent, Unit.MarketState marketState) {
        if (units.putIfAbsent(unitId, new UnitRow(unitId, propertyId, workspaceId, name, baseRent,
                marketState, null)) != null) {
            throw new IllegalStateException("duplicate key on pm_unit: " + unitId);
        }
    }

    @Override
    public void baseRentSet(UUID unitId, UUID workspaceId, BigDecimal amount) {
        update(unitId, workspaceId, row -> new UnitRow(row.unitId(), row.propertyId(),
            row.workspaceId(), row.name(), amount, row.marketState(), row.listingRef()));
    }

    @Override
    public void marketStateSet(UUID unitId, UUID workspaceId, Unit.MarketState marketState) {
        update(unitId, workspaceId, row -> new UnitRow(row.unitId(), row.propertyId(),
            row.workspaceId(), row.name(), row.baseRent(), marketState, row.listingRef()));
    }

    @Override
    public void listingRefSet(UUID unitId, UUID workspaceId, String listingRef) {
        update(unitId, workspaceId, row -> new UnitRow(row.unitId(), row.propertyId(),
            row.workspaceId(), row.name(), row.baseRent(), row.marketState(), listingRef));
    }

    /** {@code where unit_id = ? and workspace_id = ?}: no match changes nothing and says nothing. */
    private void update(UUID unitId, UUID workspaceId,
                        java.util.function.UnaryOperator<UnitRow> change) {
        var row = units.get(unitId);
        if (row == null || !row.workspaceId().equals(workspaceId)) {
            return;
        }
        units.put(unitId, change.apply(row));
    }

    public Optional<UnitRow> unit(UUID unitId) {
        return Optional.ofNullable(units.get(unitId));
    }

    public Optional<PropertyRow> property(UUID propertyId) {
        return Optional.ofNullable(properties.get(propertyId));
    }

    /** Units in one workspace, which is what every real read of this table is scoped by. */
    public java.util.List<UnitRow> unitsIn(UUID workspaceId) {
        return units.values().stream().filter(row -> row.workspaceId().equals(workspaceId)).toList();
    }
}
