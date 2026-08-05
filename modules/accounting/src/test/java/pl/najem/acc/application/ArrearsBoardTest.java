package pl.najem.acc.application;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The board a manager looks at every morning. Its job is to be worth looking at: if it shouts on
 * the ninth of every month because charges have just been posted, it stops meaning anything.
 *
 * <p>The colour is derived from the charges, never stored as an opinion — every path that settles
 * or reopens a charge re-derives it, so there is no way to reach a tenancy whose colour is stale.
 */
@Testcontainers
@Tag("integration")
class ArrearsBoardTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate JANUARY = LocalDate.of(2027, 1, 10);
    private static final LocalDate FEBRUARY = LocalDate.of(2027, 2, 10);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
    }

    /** A charge posted the day before it falls due is not arrears. Nothing has gone wrong yet. */
    @Test
    void anUnpaidChargeBeforeItsDueDateIsYellow() {
        var tenancyId = charge("NAJEM/B1/2027", "3000", FEBRUARY);

        assertThat(colourOn(tenancyId, FEBRUARY.minusDays(1))).isEqualTo(ArrearsColour.YELLOW);
    }

    /** Arrears start on the first day past due, not after a grace period nobody agreed to. */
    @Test
    void anUnpaidChargeOnTheDayAfterItsDueDateIsRed() {
        var tenancyId = charge("NAJEM/B2/2027", "3000", FEBRUARY);

        assertThat(colourOn(tenancyId, FEBRUARY)).isEqualTo(ArrearsColour.YELLOW);
        assertThat(colourOn(tenancyId, FEBRUARY.plusDays(1))).isEqualTo(ArrearsColour.RED);
    }

    /**
     * Art. 11 counts whole periods, not money. One month unpaid is the first of the three that make
     * termination available, so it is the moment the board must change its voice.
     */
    @Test
    void oneWholePeriodUnpaidIsBrightRed() {
        var tenancyId = charge("NAJEM/B3/2027", "3000", JANUARY);
        chargeFor(tenancyId, "NAJEM/B3B/2027", "3000", FEBRUARY);

        assertThat(colourOn(tenancyId, JANUARY.plusDays(1))).isEqualTo(ArrearsColour.RED);
        assertThat(colourOn(tenancyId, FEBRUARY.plusDays(1))).isEqualTo(ArrearsColour.BRIGHT_RED);
    }

    /**
     * A tenant three periods behind by a little is in a different legal position from one a single
     * period behind by a lot, even though the second owes more. The counter is periods.
     */
    @Test
    void arrearsCountPeriodsRatherThanAmounts() {
        var small = charge("NAJEM/B4/2027", "100", JANUARY);
        chargeFor(small, "NAJEM/B4B/2027", "100", FEBRUARY);
        var large = charge("NAJEM/B5/2027", "9000", FEBRUARY);

        assertThat(colourOn(small, FEBRUARY.plusDays(1))).isEqualTo(ArrearsColour.BRIGHT_RED);
        assertThat(colourOn(large, FEBRUARY.plusDays(1))).isEqualTo(ArrearsColour.RED);
    }

    @Test
    void aTenancyWithNothingOwedIsGreen() {
        var tenancyId = charge("NAJEM/B6/2027", "3000", JANUARY);
        settle(tenancyId);

        assertThat(colourOn(tenancyId, MARCH)).isEqualTo(ArrearsColour.GREEN);
    }

    /** A part payment does not clear the period. The tenant still owes, so the board still says so. */
    @Test
    void aPartlyPaidOverdueChargeStaysRed() {
        var tenancyId = charge("NAJEM/B7/2027", "3000", JANUARY);
        settlePartly(tenancyId, "1000");

        assertThat(colourOn(tenancyId, JANUARY.plusDays(1))).isEqualTo(ArrearsColour.RED);
    }

    /**
     * A grosz is not a payment period.
     *
     * <p>Art. 11 ust. 2 pkt 2 speaks of <em>zwłoka</em> lasting at least three full payment periods.
     * The delay is what must be full, not the non-payment: a tenant who pays one grosz against a
     * 3000 zł czynsz is in delay for the whole period, because the obligation for that period was
     * never discharged. Counting only periods where nothing at all arrived let a token transfer
     * reset the statutory clock every month — and the tenant who does it is exactly the tenant the
     * provision is about.
     */
    @Test
    void aTokenPaymentDoesNotStopTheStatutoryClock() {
        var tenancyId = charge("NAJEM/B10/2027", "3000", JANUARY);
        chargeFor(tenancyId, "NAJEM/B10B/2027", "3000", FEBRUARY);
        settlePartly(tenancyId, "0.01");

        assertThat(colourOn(tenancyId, FEBRUARY.plusDays(1))).isEqualTo(ArrearsColour.BRIGHT_RED);
    }

    /**
     * The colour says the clock is running; the count says how far it has run. Three full periods is
     * where termination becomes available, and a manager cannot act on a colour that means "one or
     * more" — so the number the colour was derived from is kept rather than thrown away.
     */
    @Test
    void theBoardKeepsThePeriodCountItColouredFrom() {
        var tenancyId = charge("NAJEM/B11/2027", "3000", JANUARY);
        chargeFor(tenancyId, "NAJEM/B11B/2027", "3000", FEBRUARY);
        chargeFor(tenancyId, "NAJEM/B11C/2027", "3000", MARCH);

        assertThat(colourOn(tenancyId, MARCH.plusMonths(1).plusDays(1)))
            .isEqualTo(ArrearsColour.BRIGHT_RED);
        assertThat(fullPeriodsOn(tenancyId)).isEqualTo(3);
    }

    /** Nothing overdue means no periods to count, and the stored number has to say so. */
    @Test
    void aGreenTenancyCountsNoPeriods() {
        var tenancyId = charge("NAJEM/B12/2027", "3000", JANUARY);
        settle(tenancyId);

        assertThat(colourOn(tenancyId, MARCH)).isEqualTo(ArrearsColour.GREEN);
        assertThat(fullPeriodsOn(tenancyId)).isZero();
    }

    /** A withdrawn charge is not an obligation, so it cannot colour a board. */
    @Test
    void aDeactivatedChargeDoesNotColourTheBoard() {
        var tenancyId = charge("NAJEM/B8/2027", "3000", JANUARY);
        UUID chargeId = jdbc.queryForObject(
            "select charge_id from acc_charge where tenancy_id = ? limit 1", UUID.class, tenancyId);
        invoicing().withdraw(WS, chargeId, "billed in error");

        assertThat(colourOn(tenancyId, MARCH)).isEqualTo(ArrearsColour.GREEN);
    }

    /** A deposit is not rent. It must not make a tenancy look like it is in arrears on its rent. */
    @Test
    void anUnpaidDepositDoesNotCountAsAnArrearsPeriod() {
        var tenancyId = UUID.randomUUID();
        invoicing().post(WS, tenancyId, pl.najem.acc.domain.Component.DEPOSIT,
            new BigDecimal("6000"), JANUARY, "KAUCJA/B9/2027");

        assertThat(colourOn(tenancyId, FEBRUARY.plusDays(1))).isEqualTo(ArrearsColour.RED);
    }

    private static InvoiceService invoicing() {
        return PostgresAccounting.invoiceService(store, jdbc, PostgresAccounting.warningService(jdbc));
    }

    private static ArrearsColour colourOn(UUID tenancyId, LocalDate asOf) {
        var board = PostgresAccounting.arrearsBoardService(jdbc, Clock.fixed(
            asOf.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        board.refresh(WS, tenancyId);
        return ArrearsColour.of(jdbc.queryForObject(
            "select status from acc_tenancy_status where tenancy_id = ?", String.class, tenancyId));
    }

    private static Integer fullPeriodsOn(UUID tenancyId) {
        return jdbc.queryForObject(
            "select full_periods_in_arrears from acc_tenancy_status where tenancy_id = ?",
            Integer.class, tenancyId);
    }

    private static UUID charge(String reference, String amount, LocalDate dueDate) {
        var tenancyId = UUID.randomUUID();
        chargeFor(tenancyId, reference, amount, dueDate);
        return tenancyId;
    }

    private static void chargeFor(UUID tenancyId, String reference, String amount, LocalDate dueDate) {
        invoicing().postRent(WS, tenancyId, new BigDecimal(amount), dueDate, reference);
    }

    private static void settle(UUID tenancyId) {
        jdbc.update("""
            update acc_charge set allocated_amount = amount, allocated = true
            where workspace_id = ? and tenancy_id = ?
            """, WS, tenancyId);
    }

    private static void settlePartly(UUID tenancyId, String amount) {
        jdbc.update("""
            update acc_charge set allocated_amount = ? where workspace_id = ? and tenancy_id = ?
            """, new BigDecimal(amount), WS, tenancyId);
    }

}
