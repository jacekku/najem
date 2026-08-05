package pl.najem.acc.application;

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
import pl.najem.acc.adapter.persistence.PostgresAccounting;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.ChargeDeactivated;
import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Corrections are reversal-only: an unpaid charge is deactivated, a paid one is credited. The
 * ledger never mutates a fact it has already asserted, and the two paths are not interchangeable —
 * a credit note is the document face the tenant is entitled to see.
 */
@Testcontainers
class ChargeLifecycleTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate DUE = LocalDate.of(2026, 12, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static LedgerService ledger;
    static IngestionService ingestion;
    static ReconciliationService reconciliation;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = PostgresAccounting.ledgerService(store, jdbc, new WarningService(jdbc));
        ingestion = new IngestionService((since, iban) -> java.util.List.of(), store, jdbc);
        reconciliation = PostgresAccounting.reconciliationService(store, jdbc);
    }

    @Test
    void anUnpaidChargeIsDeactivated() {
        var tenancyId = UUID.randomUUID();
        var chargeId = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), DUE, "NAJEM/CL1/2026");

        ledger.deactivateCharge(WS, chargeId, "posted in error");

        assertThat(jdbc.queryForObject("select active from acc_charge where charge_id = ?",
            Boolean.class, chargeId)).isFalse();
        assertThat(store.load(tenancyId, "TenancyLedger").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(ChargeDeactivated.class));
    }

    @Test
    void aDeactivatedChargeNoLongerAttractsPayments() {
        var tenancyId = UUID.randomUUID();
        var chargeId = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2100"), DUE, "NAJEM/CL2/2026");
        ledger.deactivateCharge(WS, chargeId, "tenant never moved in");

        ingestion.ingest(WS, new BankLine("tx-cl2", new BigDecimal("2100"), "NAJEM/CL2/2026", DUE));

        assertThat(jdbc.queryForObject("select status from acc_payment where external_id = 'tx-cl2'",
            String.class)).isEqualTo("unmatched");
    }

    @Test
    void aPaidChargeCannotBeDeactivatedAndTheRefusalNamesTheCreditNote() {
        var chargeId = paidCharge("NAJEM/CL3/2026", new BigDecimal("2200"));

        assertThatThrownBy(() -> ledger.deactivateCharge(WS, chargeId, "rent was wrong"))
            .isInstanceOf(ChargeAlreadyPaidException.class)
            .hasMessageContaining("credit note");

        assertThat(jdbc.queryForObject("select active from acc_charge where charge_id = ?",
            Boolean.class, chargeId)).isTrue();
    }

    @Test
    void aCreditNoteLeavesTheOriginalChargeStandingAsAnAuditPair() {
        var tenancyId = UUID.randomUUID();
        var chargeId = paidCharge(tenancyId, "NAJEM/CL4/2026", new BigDecimal("2300"));

        var creditNoteId = ledger.issueCreditNote(WS, chargeId, new BigDecimal("300"),
            "hot water out for a week");

        assertThat(jdbc.queryForObject("select amount from acc_charge where charge_id = ?",
            BigDecimal.class, chargeId)).isEqualByComparingTo("2300");
        assertThat(jdbc.queryForObject("select amount from acc_credit_note where credit_note_id = ?",
            BigDecimal.class, creditNoteId)).isEqualByComparingTo("300");
        assertThat(store.load(tenancyId, "TenancyLedger").events())
            .anySatisfy(e -> assertThat(e).isInstanceOf(CreditNoteIssued.class));
    }

    @Test
    void aCreditNoteCannotExceedTheChargeItCorrects() {
        var chargeId = paidCharge("NAJEM/CL5/2026", new BigDecimal("2400"));

        assertThatThrownBy(() -> ledger.issueCreditNote(WS, chargeId, new BigDecimal("2500"), "typo"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The pair is not interchangeable in either direction: an unpaid charge is corrected by deactivation. */
    @Test
    void anUnpaidChargeIsNotCorrectedByACreditNote() {
        var chargeId = ledger.postRentCharge(WS, UUID.randomUUID(), new BigDecimal("2600"), DUE,
            "NAJEM/CL6/2026");

        assertThatThrownBy(() -> ledger.issueCreditNote(WS, chargeId, new BigDecimal("100"), "discount"))
            .isInstanceOf(ChargeNotPaidException.class)
            .hasMessageContaining("deactivat");
    }

    @Test
    void creditNotesOfAnotherWorkspaceAreInvisible() {
        var chargeId = paidCharge("NAJEM/CL7/2026", new BigDecimal("2700"));
        var otherWorkspace = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

        assertThatThrownBy(() -> ledger.issueCreditNote(otherWorkspace, chargeId,
            new BigDecimal("100"), "not mine"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static UUID paidCharge(String reference, BigDecimal amount) {
        return paidCharge(UUID.randomUUID(), reference, amount);
    }

    private static UUID paidCharge(UUID tenancyId, String reference, BigDecimal amount) {
        var chargeId = ledger.postRentCharge(WS, tenancyId, amount, DUE, reference);
        ingestion.ingest(WS, new BankLine("tx-" + reference, amount, reference, DUE));
        UUID paymentId = jdbc.queryForObject(
            "select payment_id from acc_payment where external_id = ?", UUID.class, "tx-" + reference);
        reconciliation.confirm(WS, paymentId);
        return chargeId;
    }
}
