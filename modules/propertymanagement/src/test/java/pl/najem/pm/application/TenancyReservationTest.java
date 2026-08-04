package pl.najem.pm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.OverlappingTenancyException;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TenancyReservationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static PortfolioService portfolio;
    static TenancyService tenancies;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        portfolio = new PortfolioService(store, jdbc);
        tenancies = new TenancyService(store, jdbc);
    }

    @Test
    void secondOverlappingReservationOnTheSameUnitIsRejected() {
        var unitId = unit();
        tenancies.reserve(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2500"), "NAJEM/M1/A");

        assertThatThrownBy(() -> tenancies.reserve(unitId, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 9, 30), new BigDecimal("2600"), "NAJEM/M1/B"))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    @Test
    void backToBackReservationsOnOneUnitBothSucceed() {
        var unitId = unit();

        var first = tenancies.reserve(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2500"), "NAJEM/M1/A");
        var second = tenancies.reserve(unitId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 12, 31),
            new BigDecimal("2600"), "NAJEM/M1/B");

        assertThat(Unit.from(store.load(unitId).events()).periods())
            .extracting(p -> p.tenancyId()).containsExactly(first, second);
    }

    @Test
    void theSameDatesOnADifferentUnitAreFine() {
        var unitA = unit();
        var unitB = unit();
        tenancies.reserve(unitA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2500"), "NAJEM/A");

        var onB = tenancies.reserve(unitB, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2500"), "NAJEM/B");

        assertThat(onB).isNotNull();
    }

    @Test
    void aRejectedReservationLeavesNoTraceOnEitherStream() {
        var unitId = unit();
        tenancies.reserve(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2500"), "NAJEM/M1/A");
        long versionBefore = store.load(unitId).version();

        assertThatThrownBy(() -> tenancies.reserve(unitId, LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 9, 30), new BigDecimal("2600"), "NAJEM/M1/B"))
            .isInstanceOf(OverlappingTenancyException.class);

        assertThat(store.load(unitId).version()).isEqualTo(versionBefore);
        assertThat(Unit.from(store.load(unitId).events()).periods()).hasSize(1);
    }

    @Test
    void cancellingAReservationFreesTheSlotForSomeoneElse() {
        var unitId = unit();
        var cancelled = tenancies.reserve(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2500"), "NAJEM/M1/A");

        tenancies.cancelReservation(cancelled, "never signed");

        var replacement = tenancies.reserve(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30),
            new BigDecimal("2400"), "NAJEM/M1/B");
        assertThat(Unit.from(store.load(unitId).events()).periods())
            .extracting(p -> p.tenancyId()).containsExactly(replacement);
    }

    @Test
    void anIndefiniteTenancyBlocksLaterReservationsOnThatUnit() {
        var unitId = unit();
        tenancies.reserve(unitId, LocalDate.of(2026, 1, 1), null,
            new BigDecimal("2500"), "NAJEM/M1/A");

        assertThatThrownBy(() -> tenancies.reserve(unitId, LocalDate.of(2031, 1, 1),
                LocalDate.of(2031, 12, 31), new BigDecimal("2600"), "NAJEM/M1/B"))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    private static UUID unit() {
        var propertyId = portfolio.createProperty(UUID.randomUUID(), "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        return portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));
    }
}
