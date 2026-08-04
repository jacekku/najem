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
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.DocType;
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
class TenancyStartProcessTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static PortfolioService portfolio;
    static TenancyService tenancies;
    static ChecklistService checklists;
    static TenancyStartProcess process;

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
        var due = new ProcessDueStore(jdbc);
        portfolio = new PortfolioService(store, jdbc);
        tenancies = new TenancyService(store, jdbc, due);
        checklists = new ChecklistService(store);
        process = new TenancyStartProcess(due, tenancies, Clock.systemDefaultZone());
    }

    @Test
    void activatesOnStartDateWhenTheChecklistIsComplete() {
        var tenancyId = reserveStarting(LocalDate.of(2026, 9, 1));

        process.runDue(LocalDate.of(2026, 9, 1));

        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.ACTIVE);
        assertThat(activationsOf(tenancyId)).isEqualTo(1);
    }

    @Test
    void doesNotActivateBeforeTheStartDate() {
        var tenancyId = reserveStarting(LocalDate.of(2027, 3, 1));

        process.runDue(LocalDate.of(2027, 2, 28));

        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.RESERVED);
    }

    @Test
    void waitsForAnIncompleteChecklistThenActivatesLateWithoutProrating() {
        var startDate = LocalDate.of(2027, 5, 1);
        var tenancyId = reserveStarting(startDate);
        checklists.addItem(tenancyId, "keys", ChecklistPhase.PRE_ACTIVATION);

        process.runDue(startDate);
        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.RESERVED);

        checklists.completeItem(tenancyId, "keys");
        process.runDue(startDate.plusDays(4));

        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.ACTIVE);
        // No prorating: the activation carries the agreed start date, not the late date.
        assertThat(activatedOn(tenancyId)).isEqualTo(startDate);
    }

    @Test
    void cancelledReservationDisarmsTheProcess() {
        var tenancyId = reserveStarting(LocalDate.of(2027, 7, 1));
        tenancies.cancelReservation(tenancyId, "never signed");

        process.runDue(LocalDate.of(2027, 7, 1));

        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.CANCELLED);
        assertThat(activationsOf(tenancyId)).isZero();
    }

    @Test
    void runningTwiceActivatesOnlyOnce() {
        var tenancyId = reserveStarting(LocalDate.of(2027, 9, 1));

        process.runDue(LocalDate.of(2027, 9, 1));
        process.runDue(LocalDate.of(2027, 9, 2));

        // fired_at is what makes the sweep idempotent: one activation, one integration event
        assertThat(activationsOf(tenancyId)).isEqualTo(1);
        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.ACTIVE);
    }

    @Test
    void anInstytucjonalnyTenancyDoesNotAutoActivateWithoutTheNotarialDeclaration() {
        var tenancyId = reserveStarting(LocalDate.of(2027, 11, 1), LegalForm.INSTYTUCJONALNY);

        process.runDue(LocalDate.of(2027, 11, 1));

        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.RESERVED);
        assertThat(activationsOf(tenancyId)).isZero();
    }

    /**
     * The other half of the gate above, and until the declaration was attachable it was
     * unreachable: no instytucjonalny tenancy could auto-activate at all. The timer stays armed
     * rather than being cancelled, so attaching the document later lets the next sweep proceed.
     */
    @Test
    void aninstytucjonalnyTenancyAutoActivatesOnceTheDeclarationIsAttached() {
        var tenancyId = reserveStarting(LocalDate.of(2027, 12, 1), LegalForm.INSTYTUCJONALNY);

        process.runDue(LocalDate.of(2027, 12, 1));
        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.RESERVED);

        tenancies.attachDocument(tenancyId, DocType.NOTARIAL_DECLARATION, "s3://docs/akt.pdf",
            null, null, LocalDate.of(2027, 11, 20));

        process.runDue(LocalDate.of(2027, 12, 2));

        assertThat(stateOf(tenancyId)).isEqualTo(Tenancy.State.ACTIVE);
        assertThat(activationsOf(tenancyId)).isEqualTo(1);
    }

    private static UUID reserveStarting(LocalDate startDate) {
        return reserveStarting(startDate, LegalForm.ZWYKLY);
    }

    private static UUID reserveStarting(LocalDate startDate, LegalForm legalForm) {
        var propertyId = portfolio.createProperty(UUID.randomUUID(), "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var unitId = portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));
        return tenancies.reserve(new ReserveTenancy(null, null, unitId,
            List.of(UUID.randomUUID()), List.of(), startDate,
            new Term.FixedTerm(startDate.plusYears(1)), legalForm,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + startDate)).tenancyId();
    }

    /**
     * Scoped to one tenancy on purpose: runDue() sweeps every armed timer in the shared
     * container, so a bare count would depend on which other tests have run.
     */
    private static int activationsOf(UUID tenancyId) {
        return jdbc.queryForObject(
            "select count(*) from outbox where event_type = 'TenancyActivatedEvent' "
                + "and payload::text like ?", Integer.class, "%" + tenancyId + "%");
    }

    private static Tenancy.State stateOf(UUID tenancyId) {
        return Tenancy.from(store.load(tenancyId, "Tenancy").events()).state();
    }

    private static LocalDate activatedOn(UUID tenancyId) {
        return jdbc.queryForObject("select activated_on from pm_tenancy where tenancy_id = ?",
            LocalDate.class, tenancyId);
    }
}
