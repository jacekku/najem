package pl.najem.pm.application;

import pl.najem.pm.adapter.persistence.PostgresTenancyProjection;
import pl.najem.pm.adapter.persistence.PostgresProcessDueRepository;

import pl.najem.pm.adapter.persistence.PostgresPortfolioProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Term;
import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class TenancyReservationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static PortfolioService portfolio;
    static TenancyService tenancies;

    /** One agency for the whole class: these are calendar rules, not boundary rules. */
    static final UUID workspaceId = UUID.randomUUID();

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        portfolio = new PortfolioService(store, new PostgresPortfolioProjection(jdbc));
        tenancies = new TenancyService(store, new PostgresTenancyProjection(jdbc), new PostgresProcessDueRepository(jdbc));
    }

    @Test
    void secondOverlappingReservationOnTheSameUnitIsRejected() {
        var unitId = unit();
        tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2500", "NAJEM/M1/A")).tenancyId();

        assertThatThrownBy(() -> tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30), "2600", "NAJEM/M1/B")))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    @Test
    void backToBackReservationsOnOneUnitBothSucceed() {
        var unitId = unit();

        var first = tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2500", "NAJEM/M1/A")).tenancyId();
        var second = tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 12, 31), "2600", "NAJEM/M1/B")).tenancyId();

        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods())
            .extracting(p -> p.tenancyId()).containsExactly(first, second);
    }

    @Test
    void theSameDatesOnADifferentUnitAreFine() {
        var unitA = unit();
        var unitB = unit();
        tenancies.reserve(workspaceId, command(unitA, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2500", "NAJEM/A")).tenancyId();

        var onB = tenancies.reserve(workspaceId, command(unitB, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2500", "NAJEM/B")).tenancyId();

        assertThat(onB).isNotNull();
    }

    @Test
    void aRejectedReservationLeavesNoTraceOnEitherStream() {
        var unitId = unit();
        tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2500", "NAJEM/M1/A")).tenancyId();
        long versionBefore = store.load(unitId, "Unit").version();

        assertThatThrownBy(() -> tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30), "2600", "NAJEM/M1/B")))
            .isInstanceOf(OverlappingTenancyException.class);

        assertThat(store.load(unitId, "Unit").version()).isEqualTo(versionBefore);
        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods()).hasSize(1);
    }

    @Test
    void cancellingAReservationFreesTheSlotForSomeoneElse() {
        var unitId = unit();
        var cancelled = tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2500", "NAJEM/M1/A")).tenancyId();

        tenancies.cancelReservation(workspaceId, cancelled, "never signed");

        var replacement = tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "2400", "NAJEM/M1/B")).tenancyId();
        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods())
            .extracting(p -> p.tenancyId()).containsExactly(replacement);
    }

    @Test
    void anIndefiniteTenancyBlocksLaterReservationsOnThatUnit() {
        var unitId = unit();
        tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2026, 1, 1), null, "2500", "NAJEM/M1/A")).tenancyId();

        assertThatThrownBy(() -> tenancies.reserve(workspaceId, command(unitId, LocalDate.of(2031, 1, 1), LocalDate.of(2031, 12, 31), "2600", "NAJEM/M1/B")))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    private static ReserveTenancy command(UUID unitId, LocalDate start, LocalDate end,
                                          String monthlyTotal, String reference) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, end == null ? new Term.Indefinite() : new Term.FixedTerm(end),
            LegalForm.ZWYKLY, new MonthlyAmount(new BigDecimal(monthlyTotal), null),
            10, null, reference);
    }

    private static UUID unit() {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        return portfolio.addUnit(workspaceId, propertyId, "M1", new BigDecimal("2500"));
    }
}
