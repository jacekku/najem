package pl.najem.acc.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * What the arrears board says about one tenancy, derived from what that tenancy still owes.
 *
 * <p>This is a function of the open invoices and the date, and of nothing else. It used to be three
 * counting queries, which put art. 11 u.o.p.l. into a SQL parameter — the statute is the reason
 * this exists, so it belongs where it can be read and tested without a database.
 *
 * @param fullPeriodsInArrears whole payment periods the tenant has been in delay for, which is the
 *                             art. 11 counter — kept beside the colour because termination becomes
 *                             available at three, and a colour meaning "one or more" tells a
 *                             manager the clock is running but not whether it has run out
 */
public record ArrearsStanding(ArrearsColour colour, int fullPeriodsInArrears) {

    /**
     * Reads the standing off what is open today.
     *
     * <p>Yellow exists so that red means something: a charge posted the day before it falls due is
     * not arrears, and colouring it red on the day it appears would put the whole portfolio into
     * the alarm state on the ninth of every month.
     *
     * @param open  every invoice of the tenancy still owing something, deactivated ones excluded —
     *              a withdrawn charge is not an obligation and cannot colour a board
     * @param today the date the question is asked on, which is the only thing that turns a yellow
     *              tenancy red without anything about the charges changing
     */
    public static ArrearsStanding of(List<Invoice> open, LocalDate today) {
        if (open.isEmpty()) {
            return new ArrearsStanding(ArrearsColour.GREEN, 0);
        }
        if (open.stream().noneMatch(invoice -> invoice.dueDate().isBefore(today))) {
            return new ArrearsStanding(ArrearsColour.YELLOW, 0);
        }
        int fullPeriods = fullPeriodsInArrears(open, today);
        return new ArrearsStanding(
            fullPeriods >= 1 ? ArrearsColour.BRIGHT_RED : ArrearsColour.RED, fullPeriods);
    }

    /**
     * A "full period" is a whole payment period elapsed in arrears, not merely a period whose
     * charge is unpaid: art. 11 speaks of zwłoka za pełne okresy płatności. A charge one day past
     * due is arrears; a charge still unpaid a month later is a full period, and three of those is
     * where termination becomes available.
     *
     * <p>Only rent counts. An unpaid deposit is a real arrear and shows as red, but it is not a
     * rental period in arrears and must not advance the termination counter.
     *
     * <p>Being open at all is the test of non-payment, the same test the colours above use. It used
     * to be "nothing has arrived against this charge", which asked whether the tenant had paid
     * something rather than whether the obligation was discharged — so one grosz against a 3000 zł
     * czynsz reset the statutory clock, every month, forever. What must be full is the delay, not
     * the non-payment, and a tenant who part-pays is in delay for the whole period.
     *
     * <p>Periods are counted by distinct due date, so a month billed as rent plus media plus an
     * admin fee is one period in arrears rather than three.
     */
    private static int fullPeriodsInArrears(List<Invoice> open, LocalDate today) {
        LocalDate cutoff = today.minusMonths(1);
        Set<LocalDate> periods = new HashSet<>();
        for (Invoice invoice : open) {
            if (invoice.component() == Component.RENT && invoice.dueDate().isBefore(cutoff)) {
                periods.add(invoice.dueDate());
            }
        }
        return periods.size();
    }
}
