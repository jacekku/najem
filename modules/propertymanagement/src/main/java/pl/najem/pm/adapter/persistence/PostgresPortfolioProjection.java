package pl.najem.pm.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.pm.application.PortfolioProjection;
import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@link PortfolioProjection} over pm_property and pm_unit.
 *
 * <p>The enum reaches the database as {@code name()}, which makes the constant's spelling a stored
 * wire value: renaming {@code MarketState.REMOVED} is a migration, not a refactoring, and Reporting
 * already filters on it. {@code PortfolioServiceTest} pins the four spellings so the difference is
 * visible at the moment somebody tries.
 */
@Repository
public class PostgresPortfolioProjection implements PortfolioProjection {

    private final JdbcTemplate jdbc;

    public PostgresPortfolioProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void propertyCreated(UUID propertyId, UUID workspaceId, String address) {
        jdbc.update("insert into pm_property(property_id, workspace_id, address) values (?,?,?)",
            propertyId, workspaceId, address);
    }

    @Override
    public void unitAdded(UUID unitId, UUID propertyId, UUID workspaceId, String name,
                          BigDecimal baseRent, Unit.MarketState marketState) {
        jdbc.update("insert into pm_unit(unit_id, property_id, workspace_id, name, base_rent, "
                + "market_state) values (?,?,?,?,?,?)",
            unitId, propertyId, workspaceId, name, baseRent, marketState.name());
    }

    @Override
    public void baseRentSet(UUID unitId, UUID workspaceId, BigDecimal amount) {
        jdbc.update("update pm_unit set base_rent = ? where unit_id = ? and workspace_id = ?",
            amount, unitId, workspaceId);
    }

    @Override
    public void marketStateSet(UUID unitId, UUID workspaceId, Unit.MarketState marketState) {
        jdbc.update("update pm_unit set market_state = ? where unit_id = ? and workspace_id = ?",
            marketState.name(), unitId, workspaceId);
    }

    @Override
    public void listingRefSet(UUID unitId, UUID workspaceId, String listingRef) {
        jdbc.update("update pm_unit set listing_ref = ? where unit_id = ? and workspace_id = ?",
            listingRef, unitId, workspaceId);
    }
}
