package pl.najem.pm.application;

import com.fasterxml.jackson.databind.JsonNode;
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
import pl.najem.contracts.events.RentChangeAppliedEvent;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RentChangeProcessTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static PortfolioService portfolio;
    static TenancyService tenancies;
    static RentChangeProcess process;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        registry.register(RentChangeAppliedEvent.class);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        var due = new ProcessDueStore(jdbc);
        portfolio = new PortfolioService(store, jdbc);
        tenancies = new TenancyService(store, jdbc, due);
        process = new RentChangeProcess(due, tenancies, store, Clock.systemDefaultZone());
    }

    @Test
    void appliesTheChangeOneDayBeforeItsEffectiveDate() {
        var tenancyId = activeTenancy();
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE);

        process.runDue(LocalDate.of(2026, 5, 30));
        assertThat(monthlyTotalOf(tenancyId)).isEqualByComparingTo("2500");

        process.runDue(LocalDate.of(2026, 5, 31));

        assertThat(monthlyTotalOf(tenancyId)).isEqualByComparingTo("2600");
        assertThat(rentChangeEventsFor(tenancyId)).isEqualTo(1);
    }

    @Test
    void cancelledChangeSendsNothing() {
        var tenancyId = activeTenancy();
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE);
        tenancies.cancelRentChange(tenancyId, LocalDate.of(2026, 6, 1));

        process.runDue(LocalDate.of(2026, 5, 31));

        assertThat(rentChangeEventsFor(tenancyId)).isZero();
        assertThat(monthlyTotalOf(tenancyId)).isEqualByComparingTo("2500");
    }

    @Test
    void theBreakdownRidesTheChangeBecauseValorizationNeedsTheRentComponent() {
        var tenancyId = activeTenancy();
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 7, 1),
            new MonthlyAmount(new BigDecimal("2800"),
                new MonthlyAmount.Breakdown(new BigDecimal("2400"), new BigDecimal("200"),
                    new BigDecimal("200"))),
            ChangeType.INDEXATION);

        process.runDue(LocalDate.of(2026, 6, 30));

        var payload = rentChangePayloadFor(tenancyId);
        assertThat(payload.get("newMonthlyTotal").decimalValue()).isEqualByComparingTo("2800");
        assertThat(payload.get("newRent").decimalValue()).isEqualByComparingTo("2400");
        assertThat(payload.get("newAdminFee").decimalValue()).isEqualByComparingTo("200");
        assertThat(payload.get("newMediaAdvance").decimalValue()).isEqualByComparingTo("200");
        assertThat(payload.get("changeType").asText()).isEqualTo("indexation");
    }

    @Test
    void twoQueuedChangesBothApplyInOrder() {
        var tenancyId = activeTenancy();
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE);
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 10, 1),
            new MonthlyAmount(new BigDecimal("2700"), null), ChangeType.AGREED_CHANGE);

        process.runDue(LocalDate.of(2026, 3, 31));
        assertThat(monthlyTotalOf(tenancyId)).isEqualByComparingTo("2600");

        // the second change must not be stranded behind the first one's fired timer
        process.runDue(LocalDate.of(2026, 9, 30));

        assertThat(monthlyTotalOf(tenancyId)).isEqualByComparingTo("2700");
        assertThat(rentChangeEventsFor(tenancyId)).isEqualTo(2);
    }

    @Test
    void runningTwiceAppliesOnlyOnce() {
        var tenancyId = activeTenancy();
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 3, 15), LocalDate.of(2027, 2, 1),
            new MonthlyAmount(new BigDecimal("2900"), null), ChangeType.AGREED_CHANGE);

        process.runDue(LocalDate.of(2027, 1, 31));
        process.runDue(LocalDate.of(2027, 2, 1));

        assertThat(rentChangeEventsFor(tenancyId)).isEqualTo(1);
    }

    private static UUID activeTenancy() {
        var propertyId = portfolio.createProperty(UUID.randomUUID(), "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var unitId = portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));
        var tenancyId = tenancies.reserve(new ReserveTenancy(null, null, unitId,
            List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 1, 1),
            new Term.FixedTerm(LocalDate.of(2028, 12, 31)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        tenancies.activate(tenancyId, LocalDate.of(2026, 1, 1));
        return tenancyId;
    }

    private static BigDecimal monthlyTotalOf(UUID tenancyId) {
        return Tenancy.from(store.load(tenancyId).events()).monthly().total();
    }

    private static int rentChangeEventsFor(UUID tenancyId) {
        return jdbc.queryForObject("select count(*) from outbox where event_type = ? "
                + "and payload::text like ?", Integer.class,
            "RentChangeAppliedEvent", "%" + tenancyId + "%");
    }

    private static JsonNode rentChangePayloadFor(UUID tenancyId) {
        String json = jdbc.queryForObject("select payload::text from outbox where event_type = ? "
                + "and payload::text like ? limit 1", String.class,
            "RentChangeAppliedEvent", "%" + tenancyId + "%");
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox payload: " + json, e);
        }
    }
}
