package pl.najem.acc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the arrears board says, stated as a function of what is owed and what day it is.
 *
 * <p>Every one of these used to need a Postgres container to ask, because the rules lived in three
 * counting queries. They are rules about obligations and a statute, so they are asked here instead
 * — {@code ArrearsBoardTest} still proves the same answers survive the round trip through the
 * database, and when the two disagree the database is right.
 */
class ArrearsStandingTest {

    private static final LocalDate JANUARY = LocalDate.of(2027, 1, 10);
    private static final LocalDate FEBRUARY = LocalDate.of(2027, 2, 10);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 10);

    @Test
    void aTenancyThatOwesNothingIsGreen() {
        var standing = ArrearsStanding.of(List.of(), FEBRUARY);

        assertThat(standing.colour()).isEqualTo(ArrearsColour.GREEN);
        assertThat(standing.fullPeriodsInArrears()).isZero();
    }

    /** A charge posted the day before it falls due is not arrears. Nothing has gone wrong yet. */
    @Test
    void anUnpaidChargeBeforeItsDueDateIsYellow() {
        var standing = ArrearsStanding.of(List.of(rent(FEBRUARY)), FEBRUARY.minusDays(1));

        assertThat(standing.colour()).isEqualTo(ArrearsColour.YELLOW);
        assertThat(standing.fullPeriodsInArrears()).isZero();
    }

    /** Arrears start on the first day past due, not after a grace period nobody agreed to. */
    @Test
    void anUnpaidChargeOnItsDueDateIsStillYellowAndTheDayAfterIsRed() {
        assertThat(ArrearsStanding.of(List.of(rent(FEBRUARY)), FEBRUARY).colour())
            .isEqualTo(ArrearsColour.YELLOW);
        assertThat(ArrearsStanding.of(List.of(rent(FEBRUARY)), FEBRUARY.plusDays(1)).colour())
            .isEqualTo(ArrearsColour.RED);
    }

    /**
     * Art. 11 counts whole periods, not money. One month unpaid is the first of the three that make
     * termination available, so it is the moment the board must change its voice.
     */
    @Test
    void rentUnpaidAWholeMonthPastDueIsAFullPeriodAndTurnsTheBoardBrightRed() {
        var standing = ArrearsStanding.of(List.of(rent(JANUARY)), FEBRUARY.plusDays(1));

        assertThat(standing.colour()).isEqualTo(ArrearsColour.BRIGHT_RED);
        assertThat(standing.fullPeriodsInArrears()).isOne();
    }

    /**
     * The statute counts delay, not non-payment. A tenant who pays a token amount against a period
     * is still in delay for that period — counting only untouched charges let one grosz reset the
     * clock every month, forever.
     */
    @Test
    void aPartPaidPeriodIsStillAPeriodInDelay() {
        var standing = ArrearsStanding.of(List.of(rent(JANUARY, "2999.99")), FEBRUARY.plusDays(1));

        assertThat(standing.fullPeriodsInArrears()).isOne();
    }

    /**
     * An unpaid deposit is a real arrear and shows as red, but it is not a rental period in
     * arrears: it is a one-off obligation, and it must not advance the termination counter.
     */
    @Test
    void anUnpaidDepositColoursTheBoardWithoutAdvancingTheStatutoryCounter() {
        var deposit = new Invoice(UUID.randomUUID(), Component.DEPOSIT, JANUARY,
            new BigDecimal("6000"));

        var standing = ArrearsStanding.of(List.of(deposit), MARCH);

        assertThat(standing.colour()).isEqualTo(ArrearsColour.RED);
        assertThat(standing.fullPeriodsInArrears()).isZero();
    }

    /**
     * A month is one period however many lines it was billed as. Rent plus media plus an admin fee
     * falling due together is one period in arrears, not three, or the counter would reach
     * termination in a single month.
     */
    @Test
    void oneMonthBilledAsSeveralLinesIsOnePeriod() {
        var standing = ArrearsStanding.of(
            List.of(rent(JANUARY), rent(JANUARY),
                new Invoice(UUID.randomUUID(), Component.MEDIA_ADVANCE, JANUARY,
                    new BigDecimal("300"))),
            MARCH);

        assertThat(standing.fullPeriodsInArrears()).isOne();
    }

    /** Three is where termination becomes available, so the count has to keep going past one. */
    @Test
    void threeUnpaidMonthsCountAsThreePeriods() {
        var standing = ArrearsStanding.of(
            List.of(rent(JANUARY), rent(FEBRUARY), rent(MARCH)), MARCH.plusMonths(2));

        assertThat(standing.colour()).isEqualTo(ArrearsColour.BRIGHT_RED);
        assertThat(standing.fullPeriodsInArrears()).isEqualTo(3);
    }

    /**
     * Overdue but not yet a full period: red without the counter running. This is the case that
     * separates "the tenant is late" from "the clock towards termination has started".
     */
    @Test
    void aChargeDaysPastDueIsRedWithNoPeriodCounted() {
        var standing = ArrearsStanding.of(List.of(rent(FEBRUARY)), FEBRUARY.plusDays(5));

        assertThat(standing.colour()).isEqualTo(ArrearsColour.RED);
        assertThat(standing.fullPeriodsInArrears()).isZero();
    }

    private static Invoice rent(LocalDate dueDate) {
        return rent(dueDate, "3000");
    }

    private static Invoice rent(LocalDate dueDate, String owed) {
        return new Invoice(UUID.randomUUID(), Component.RENT, dueDate, new BigDecimal(owed));
    }
}
