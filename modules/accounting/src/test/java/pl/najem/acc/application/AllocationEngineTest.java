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
import pl.najem.acc.domain.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One transfer settles charges, not a charge. Oldest due first; within one due date the components
 * settle interest, then media, then admin, then repair recharges, and rent last — the tenant's
 * cheapest obligations are cleared before the one that carries the arrears consequences.
 *
 * <p>Money is conserved at every step: what a payment settles plus what it leaves as credit is
 * exactly what arrived.
 */
@Testcontainers
class AllocationEngineTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final UUID OTHER_WS = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate JANUARY = LocalDate.of(2027, 1, 10);
    private static final LocalDate FEBRUARY = LocalDate.of(2027, 2, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static LedgerService ledger;
    static IngestionService ingestion;
    /**
     * Allocation is exercised through the orchestrator rather than directly, because that is the
     * route every caller takes: it is what settles the charges and leaves the arrears board saying
     * something true about them. Reaching past it would test the arithmetic and miss the pairing.
     */
    static AccountingService allocation;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        ledger = new LedgerService(store, jdbc, new WarningService(jdbc));
        ingestion = new IngestionService((since, iban) -> List.of(), store, jdbc);
        allocation = PostgresAccounting.accountingService(store, jdbc);
    }

    @Test
    void oneTransferSettlesSeveralChargesOldestFirst() {
        var tenancyId = UUID.randomUUID();
        var january = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), JANUARY, "NAJEM/A1/2027");
        var february = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), FEBRUARY, "NAJEM/A1B/2027");
        var paymentId = ingest("tx-a1", "4000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settled(january)).isEqualByComparingTo("2000");
        assertThat(settled(february)).isEqualByComparingTo("2000");
        assertThat(unallocated(paymentId)).isEqualByComparingTo("0");
    }

    /**
     * Rent last is the whole point of the order: rent is what the arrears board and the art. 11
     * full-periods counter watch, so leaving it unpaid is the outcome that stays visible.
     */
    @Test
    void withinOneDueDateRentSettlesLast() {
        var tenancyId = UUID.randomUUID();
        var charges = ledger.postMonthlyCharges(WS, tenancyId,
            MonthlyBreakdown.split(new BigDecimal("3000"), new BigDecimal("2400"),
                new BigDecimal("300"), new BigDecimal("300")),
            JANUARY, "NAJEM/A2/2027").chargeIds();
        var paymentId = ingest("tx-a2", "600");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settledFor(tenancyId, Component.MEDIA_ADVANCE)).isEqualByComparingTo("300");
        assertThat(settledFor(tenancyId, Component.ADMIN_FEE)).isEqualByComparingTo("300");
        assertThat(settledFor(tenancyId, Component.RENT)).isEqualByComparingTo("0");
        assertThat(charges).hasSize(3);
    }

    @Test
    void aPartPaymentSettlesWhatItReachesAndLeavesTheRestOpen() {
        var tenancyId = UUID.randomUUID();
        var january = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), JANUARY, "NAJEM/A3/2027");
        var february = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), FEBRUARY, "NAJEM/A3B/2027");
        var paymentId = ingest("tx-a3", "2500");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settled(january)).isEqualByComparingTo("2000");
        assertThat(settled(february)).isEqualByComparingTo("500");
        assertThat(isFullySettled(january)).isTrue();
        assertThat(isFullySettled(february)).isFalse();
    }

    /** Money the tenant does not owe yet stays theirs: it is credit on the payment, not on a charge. */
    @Test
    void anOverpaymentLeavesCreditRatherThanOversettlingACharge() {
        var tenancyId = UUID.randomUUID();
        var january = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), JANUARY, "NAJEM/A4/2027");
        var paymentId = ingest("tx-a4", "2600");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settled(january)).isEqualByComparingTo("2000");
        assertThat(unallocated(paymentId)).isEqualByComparingTo("600");
        assertThat(statusOf(paymentId)).isEqualTo("partially-allocated");
    }

    /** What was settled plus what was left is exactly what arrived. Nothing is created or lost. */
    @Test
    void everyZlotyIsEitherSettledOrLeftAsCredit() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WS, tenancyId, new BigDecimal("1500"), JANUARY, "NAJEM/A5/2027");
        ledger.postRentCharge(WS, tenancyId, new BigDecimal("1500"), FEBRUARY, "NAJEM/A5B/2027");
        var paymentId = ingest("tx-a5", "3700");

        allocation.allocate(WS, paymentId, tenancyId);

        BigDecimal allocated = jdbc.queryForObject(
            "select coalesce(sum(amount), 0) from acc_allocation where payment_id = ?",
            BigDecimal.class, paymentId);
        assertThat(allocated.add(unallocated(paymentId))).isEqualByComparingTo("3700");
    }

    /** A withdrawn charge is not an obligation, so money must not come to rest on it. */
    @Test
    void aDeactivatedChargeIsNotSettled() {
        var tenancyId = UUID.randomUUID();
        var withdrawn = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), JANUARY, "NAJEM/A6/2027");
        var standing = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), FEBRUARY, "NAJEM/A6B/2027");
        ledger.deactivateCharge(WS, withdrawn, "billed in error");
        var paymentId = ingest("tx-a6", "2000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settled(withdrawn)).isEqualByComparingTo("0");
        assertThat(settled(standing)).isEqualByComparingTo("2000");
    }

    /**
     * A deposit is a separate transfer against a separate obligation, so rent money must not drift
     * onto it. Settling a deposit is an explicit act, not a side effect of the monthly cycle.
     */
    @Test
    void rentMoneyDoesNotDriftOntoADepositCharge() {
        var tenancyId = UUID.randomUUID();
        var deposit = ledger.postCharge(WS, tenancyId, Component.DEPOSIT, new BigDecimal("6000"),
            JANUARY, "KAUCJA/A7/2027");
        var rent = ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), FEBRUARY, "NAJEM/A7/2027");
        var paymentId = ingest("tx-a7", "2000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settled(deposit)).isEqualByComparingTo("0");
        assertThat(settled(rent)).isEqualByComparingTo("2000");
    }

    /** The boundary is the query: another agency's charges are not visible to settle. */
    @Test
    void allocationNeverReachesAnotherWorkspacesCharges() {
        var tenancyId = UUID.randomUUID();
        var theirs = ledger.postRentCharge(OTHER_WS, tenancyId, new BigDecimal("2000"), JANUARY,
            "OBCE/A8/2027");
        var paymentId = ingest("tx-a8", "2000");

        allocation.allocate(WS, paymentId, tenancyId);

        assertThat(settled(theirs)).isEqualByComparingTo("0");
        assertThat(unallocated(paymentId)).isEqualByComparingTo("2000");
    }

    /**
     * The absence has to be proved here rather than only against a fake. The in-memory repository
     * can show that allocation raises when handed an empty answer; only the real statement can show
     * that a missing row <em>is</em> an empty answer rather than a thrown query.
     */
    @Test
    void allocatingAPaymentThatDoesNotExistIsAnError() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WS, tenancyId, new BigDecimal("2000"), JANUARY, "NAJEM/A9/2027");
        var unknown = UUID.randomUUID();

        assertThatThrownBy(() -> allocation.allocate(WS, unknown, tenancyId))
            .isInstanceOf(PaymentNotFoundException.class)
            .hasMessageContaining(unknown.toString());
    }

    /** The workspace is part of the lookup, so another agency's payment is absent rather than denied. */
    @Test
    void aPaymentInAnotherWorkspaceDoesNotExistHere() {
        var tenancyId = UUID.randomUUID();
        var paymentId = ingest("tx-a10", "2000");

        assertThatThrownBy(() -> allocation.allocate(OTHER_WS, paymentId, tenancyId))
            .isInstanceOf(PaymentNotFoundException.class);
    }

    private static UUID ingest(String externalId, String amount) {
        ingestion.ingest(WS, new BankLine(externalId, new BigDecimal(amount), "", JANUARY));
        return jdbc.queryForObject("select payment_id from acc_payment where external_id = ?",
            UUID.class, externalId);
    }

    private static BigDecimal settled(UUID chargeId) {
        return jdbc.queryForObject("select allocated_amount from acc_charge where charge_id = ?",
            BigDecimal.class, chargeId);
    }

    private static BigDecimal settledFor(UUID tenancyId, Component component) {
        return jdbc.queryForObject("""
            select coalesce(sum(allocated_amount), 0) from acc_charge
            where tenancy_id = ? and component = ?
            """, BigDecimal.class, tenancyId, component.wireName());
    }

    private static boolean isFullySettled(UUID chargeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select allocated from acc_charge where charge_id = ?", Boolean.class, chargeId));
    }

    private static BigDecimal unallocated(UUID paymentId) {
        return jdbc.queryForObject("select unallocated_amount from acc_payment where payment_id = ?",
            BigDecimal.class, paymentId);
    }

    private static String statusOf(UUID paymentId) {
        return jdbc.queryForObject("select status from acc_payment where payment_id = ?",
            String.class, paymentId);
    }
}
