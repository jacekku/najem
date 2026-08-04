package pl.najem.pm.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.MoveOutProtocolRecordedEvent;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.contracts.events.TenancyEndedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.pm.PmEventTypes;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.HandoverProtocol;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MeterReading;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Term;
import pl.najem.pm.domain.Unit;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class EndOfTenancyProcessTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ProcessDueStore due;
    static PortfolioService portfolio;
    static TenancyService tenancies;
    static ChecklistService checklists;
    static EndOfTenancyProcess process;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        PmEventTypes.register(registry);
        registry.register(TenancyActivatedEvent.class);
        registry.register(TenancyEndedEvent.class);
        registry.register(MoveOutProtocolRecordedEvent.class);
        store = new JdbcEventStore(jdbc, TestMapper.productionLike(), registry);
        due = new ProcessDueStore(jdbc);
        portfolio = new PortfolioService(store, jdbc);
        tenancies = new TenancyService(store, jdbc, due);
        checklists = new ChecklistService(store);
        process = new EndOfTenancyProcess(due, tenancies, Clock.systemDefaultZone());
    }

    @Test
    void fireEndingSoonOneMonthBeforeTheEnd() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        process.runDue(LocalDate.of(2027, 7, 30));
        assertThat(endingSoon(tenancyId)).isFalse();

        process.runDue(LocalDate.of(2027, 7, 31));
        assertThat(endingSoon(tenancyId)).isTrue();
    }

    @Test
    void terminationNoticeRearmsEndingSoonToTheEarlierDate() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));
        tenancies.giveTerminationNotice(tenancyId, "tenant notice", LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 12, 31), "s3://docs/notice.pdf");

        process.runDue(LocalDate.of(2026, 11, 30));

        assertThat(endingSoon(tenancyId)).isTrue();
    }

    /**
     * Nothing to arm and that is not an error: an indefinite tenancy has no end date until
     * somebody gives notice, and a manager should not have to invent one to satisfy a timer.
     */
    @Test
    void anIndefiniteTenancyArmsNothingUntilNoticeIsGiven() {
        var tenancyId = activeIndefiniteTenancy();

        assertThat(armedDate(EndOfTenancyProcess.KIND, tenancyId)).isNull();

        tenancies.giveTerminationNotice(tenancyId, "landlord notice", LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 4, 30), null);

        assertThat(armedDate(EndOfTenancyProcess.KIND, tenancyId))
            .isEqualTo(LocalDate.of(2027, 3, 30));
    }

    @Test
    void endingWithBackToMarketReopensTheUnitAndFreesTheCalendar() {
        var unitId = openUnit();
        var tenancyId = activeTenancyOn(unitId, LocalDate.of(2027, 8, 31));

        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY, "", true));

        assertThat(Unit.from(store.load(unitId, "Unit").events()).marketState())
            .isEqualTo(Unit.MarketState.OPEN);
        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods()).isEmpty();
    }

    /**
     * The manager may be renovating, selling, or moving family in. PM frees the calendar either
     * way — the period is gone, so a new tenancy can be reserved — but does not advertise a unit
     * nobody said was available.
     */
    @Test
    void endingWithoutBackToMarketFreesTheCalendarButLeavesTheUnitClosed() {
        var unitId = openUnit();
        var tenancyId = activeTenancyOn(unitId, LocalDate.of(2027, 8, 31));

        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.MUTUAL_AGREEMENT, "renovation planned", false));

        assertThat(Unit.from(store.load(unitId, "Unit").events()).marketState())
            .isNotEqualTo(Unit.MarketState.OPEN);
        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods()).isEmpty();
    }

    @Test
    void endingArmsTheDepositSettlementDeadlineOneMonthAfterVacating() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY, "", true));

        assertThat(armedDate(EndOfTenancyProcess.DEPOSIT_SETTLEMENT_KIND, tenancyId))
            .isEqualTo(LocalDate.of(2027, 10, 2));
    }

    /** Nobody moved in, so there is no vacate date and no settlement clock to start. */
    @Test
    void anAnnulledTenancyArmsNoDepositDeadline() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2026, 9, 2), null,
            EndReason.ERROR_ANNULLED, "wrong unit", false));

        assertThat(armedDate(EndOfTenancyProcess.DEPOSIT_SETTLEMENT_KIND, tenancyId)).isNull();
    }

    @Test
    void endingDisarmsTheStartAndRentChangeTimers() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));
        tenancies.scheduleRentChange(tenancyId, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), pl.najem.pm.domain.ChangeType.AGREED_CHANGE);

        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2026, 11, 30),
            LocalDate.of(2026, 12, 1), EndReason.MUTUAL_AGREEMENT, "", true));

        assertThat(armedDate(RentChangeProcess.KIND, tenancyId)).isNull();
        assertThat(armedDate(TenancyStartProcess.KIND, tenancyId)).isNull();
        assertThat(armedDate(EndOfTenancyProcess.KIND, tenancyId)).isNull();
    }

    /** Accounting's deposit-settlement clock is max(vacateDate, protocolDate) + 1 month. */
    @Test
    void endingPublishesBothDatesAndTheUnitToAccounting() {
        var unitId = openUnit();
        var tenancyId = activeTenancyOn(unitId, LocalDate.of(2027, 8, 31));

        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY, "keys returned", true));

        var payload = outboxPayload("TenancyEndedEvent", tenancyId);
        assertThat(payload.get("unitId").asText()).isEqualTo(unitId.toString());
        assertThat(payload.get("endDate").asText()).isEqualTo("2027-08-31");
        assertThat(payload.get("vacateDate").asText()).isEqualTo("2027-09-02");
        assertThat(payload.get("reasonType").asText()).isEqualTo("agreement-expiry");
        assertThat(payload.get("workspaceId").isNull()).isFalse();
    }

    /** The other input to that clock, and the readings Accounting trues media up against. */
    @Test
    void theMoveOutProtocolIsPublishedButTheMoveInOneIsNot() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        checklists.recordHandover(tenancyId, new HandoverProtocol(ChecklistPhase.PRE_ACTIVATION,
            List.of(new MeterReading("cw-1", "cold-water", new BigDecimal("100"))),
            "clean", List.of(), null, LocalDate.of(2026, 9, 1)));

        assertThat(outboxCount("MoveOutProtocolRecordedEvent", tenancyId)).isZero();

        checklists.recordHandover(tenancyId, new HandoverProtocol(ChecklistPhase.END_OF_TENANCY,
            List.of(new MeterReading("cw-1", "cold-water", new BigDecimal("189.5"))),
            "scuffed wall", List.of(), "s3://docs/moveout.pdf", LocalDate.of(2027, 9, 2)));

        var payload = outboxPayload("MoveOutProtocolRecordedEvent", tenancyId);
        assertThat(payload.get("protocolDate").asText()).isEqualTo("2027-09-02");
        assertThat(payload.get("readings")).hasSize(1);
        assertThat(payload.get("readings").get(0).get("reading").decimalValue())
            .isEqualByComparingTo("189.5");
    }

    /**
     * One unprocessable subject must not take the sweep down with it. The whole loop shares a
     * transaction, so before this was isolated a single bad row rolled back every other tenancy's
     * work in the same sweep and threw again on the next one — forever, with nothing committed
     * and nothing logged. That is how the seq-103 stream collision would have surfaced in
     * production: not as an error somebody saw, but as a process manager that quietly did nothing.
     */
    @Test
    void oneUnprocessableTimerDoesNotStopTheRest() {
        var vanished = UUID.randomUUID();
        due.arm(EndOfTenancyProcess.KIND, vanished, LocalDate.of(2027, 7, 31));
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        var result = process.runDue(LocalDate.of(2027, 7, 31));

        assertThat(endingSoon(tenancyId)).isTrue();
        assertThat(result.failed()).contains(vanished);
    }

    /** A timer whose subject is not a tenancy is a corrupt row, not something to skip silently. */
    @Test
    void aTimerAgainstAnUnknownSubjectIsReportedRatherThanIgnored() {
        var vanished = UUID.randomUUID();
        due.arm(EndOfTenancyProcess.KIND, vanished, LocalDate.of(2027, 7, 31));

        assertThat(process.runDue(LocalDate.of(2027, 7, 31)).failed()).contains(vanished);
        // and it stays armed, so the problem does not disappear on the next sweep
        assertThat(armedDate(EndOfTenancyProcess.KIND, vanished))
            .isEqualTo(LocalDate.of(2027, 7, 31));
    }

    @Test
    void endingAnAlreadyEndedTenancyIsRejected() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));
        tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 9, 2), EndReason.AGREEMENT_EXPIRY, "", true));

        assertThatThrownBy(() -> tenancies.end(tenancyId, new EndTenancy(LocalDate.of(2027, 9, 30),
                null, EndReason.MUTUAL_AGREEMENT, "", true)))
            .isInstanceOf(IllegalStateException.class);
    }

    // --- fixtures ---

    private static UUID openUnit() {
        var propertyId = portfolio.createProperty(UUID.randomUUID(), "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var unitId = portfolio.addUnit(propertyId, "M1", new BigDecimal("2500"));
        portfolio.openUnitToRent(unitId, "ready");
        return unitId;
    }

    private static UUID activeTenancyOn(UUID unitId, LocalDate endDate) {
        var tenancyId = tenancies.reserve(new ReserveTenancy(null, null, unitId,
            List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(endDate), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, new BigDecimal("2500"),
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));
        return tenancyId;
    }

    private static UUID activeTenancyEnding(LocalDate endDate) {
        return activeTenancyOn(openUnit(), endDate);
    }

    private static UUID activeIndefiniteTenancy() {
        var tenancyId = tenancies.reserve(new ReserveTenancy(null, null, openUnit(),
            List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 9, 1),
            new Term.Indefinite(), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        tenancies.activate(tenancyId, LocalDate.of(2026, 9, 1));
        return tenancyId;
    }

    private static boolean endingSoon(UUID tenancyId) {
        return Tenancy.from(store.load(tenancyId, "Tenancy").events()).endingSoon();
    }

    private static LocalDate armedDate(String kind, UUID subjectId) {
        return jdbc.query("select due_on from pm_process_due where kind = ? and subject_id = ?",
            rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, kind, subjectId);
    }

    private static int outboxCount(String eventType, UUID tenancyId) {
        return jdbc.queryForObject("select count(*) from outbox where event_type = ? "
            + "and payload::text like ?", Integer.class, eventType, "%" + tenancyId + "%");
    }

    private static JsonNode outboxPayload(String eventType, UUID tenancyId) {
        String json = jdbc.queryForObject("select payload::text from outbox where event_type = ? "
                + "and payload::text like ? limit 1", String.class,
            eventType, "%" + tenancyId + "%");
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox payload: " + json, e);
        }
    }
}
