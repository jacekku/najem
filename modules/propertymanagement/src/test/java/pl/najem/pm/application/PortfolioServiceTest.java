package pl.najem.pm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Unit;
import pl.najem.pm.domain.UnitEvents;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PortfolioServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static PortfolioService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        service = new PortfolioService(store, jdbc);
    }

    @Test
    void createsPropertyAndAddsUnitWithProjectionRow() {
        var workspaceId = UUID.randomUUID();
        var propertyId = service.createProperty(workspaceId, "Testowa 1, Kraków", owners());
        var unitId = service.addUnit(propertyId, "M1", new BigDecimal("2500"));

        assertThat(store.load(unitId).events()).containsExactly(
            new UnitEvents.UnitAddedToProperty(workspaceId, unitId, propertyId, "M1", new BigDecimal("2500")));
        Integer rows = jdbc.queryForObject("select count(*) from pm_unit where unit_id = ?", Integer.class, unitId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void createdPropertyOwnsItsWorkspaceAndUnitsInheritIt() {
        var workspaceId = UUID.randomUUID();

        var propertyId = service.createProperty(workspaceId, "Testowa 1, Kraków", owners());
        var unitId = service.addUnit(propertyId, "M1", new BigDecimal("2500"));

        assertThat(service.workspaceOf(propertyId)).isEqualTo(workspaceId);
        UUID unitWorkspace = jdbc.queryForObject(
            "select workspace_id from pm_unit where unit_id = ?", UUID.class, unitId);
        assertThat(unitWorkspace).isEqualTo(workspaceId);
    }

    @Test
    void twoPropertiesInDifferentWorkspacesDoNotShareUnits() {
        var workspaceA = UUID.randomUUID();
        var workspaceB = UUID.randomUUID();
        var unitA = service.addUnit(service.createProperty(workspaceA, "A 1", owners()), "M1",
            new BigDecimal("2500"));
        service.addUnit(service.createProperty(workspaceB, "B 1", owners()), "M1", new BigDecimal("2500"));

        var unitsInA = jdbc.queryForList("select unit_id from pm_unit where workspace_id = ?",
            UUID.class, workspaceA);

        assertThat(unitsInA).containsExactly(unitA);
    }

    @Test
    void marketStateAndListingRefTrackTheUnitStreamIntoTheProjection() {
        var propertyId = service.createProperty(UUID.randomUUID(), "Testowa 1", owners());
        var unitId = service.addUnit(propertyId, "M12", new BigDecimal("2600"));

        assertThat(marketStateOf(unitId)).isEqualTo("INVENTORY");

        service.updateUnitDetails(unitId, Map.of("listingRef", "OLX-99887"));
        service.openUnitToRent(unitId, "listed");

        assertThat(marketStateOf(unitId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select listing_ref from pm_unit where unit_id = ?",
            String.class, unitId)).isEqualTo("OLX-99887");
        assertThat(Unit.from(store.load(unitId).events()).marketState())
            .isEqualTo(Unit.MarketState.OPEN);

        service.closeUnitToRent(unitId, "renovation");

        assertThat(marketStateOf(unitId)).isEqualTo("CLOSED");
    }

    @Test
    void baseRentChangeReachesBothTheStreamAndTheProjection() {
        var propertyId = service.createProperty(UUID.randomUUID(), "Testowa 1", owners());
        var unitId = service.addUnit(propertyId, "M12", new BigDecimal("2600"));

        service.setUnitBaseRent(unitId, new BigDecimal("2400"));

        assertThat(Unit.from(store.load(unitId).events()).baseRent()).isEqualByComparingTo("2400");
        assertThat(jdbc.queryForObject("select base_rent from pm_unit where unit_id = ?",
            BigDecimal.class, unitId)).isEqualByComparingTo("2400");
    }

    private static String marketStateOf(UUID unitId) {
        return jdbc.queryForObject("select market_state from pm_unit where unit_id = ?",
            String.class, unitId);
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }
}
