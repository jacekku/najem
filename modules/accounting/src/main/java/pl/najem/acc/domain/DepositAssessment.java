package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What the statutory cap has to say about a deposit, decided before anything is recorded.
 *
 * <p>Art. 6 caps the deposit at a multiple of the czynsz, and the multiple differs by legal form.
 * Three things can be true and they are not the same thing: the deposit exceeds the cap, the cap
 * could not be checked, or the form it would be checked against is not one this system knows.
 *
 * <p>Nothing here refuses. A cap that blocked would produce a deposit the system never recorded,
 * which is a deposit nobody can return — the manager is told and decides. This type answers the
 * questions; the sentences a human reads are written where humans are being written to.
 */
public record DepositAssessment(BigDecimal multiplier, boolean capCheckable, boolean capExceeded,
                                boolean formRecognised, int capInMonths, String formName) {

    public static DepositAssessment assess(BigDecimal amount, BigDecimal rentAtCharge,
                                           String legalForm) {
        // The cap is a multiple of the czynsz, so with no czynsz there is no multiple to compare
        // and the check cannot run. Saying so is the whole point: a multiplier of zero is not
        // greater than any cap, so reporting one reports a check that never happened — and a
        // contract putting its whole monthly into adminFee and mediaAdvance is exactly how a
        // deposit would be placed beyond the cap's reach.
        boolean capCheckable = rentAtCharge != null && rentAtCharge.signum() > 0;
        BigDecimal multiplier = multiplierOf(amount, rentAtCharge);
        var form = LegalForm.of(legalForm);
        if (form.isEmpty()) {
            return new DepositAssessment(multiplier, capCheckable, false, false, 0, null);
        }
        int cap = form.get().depositCapInMonths();
        boolean exceeded =
            capCheckable && multiplier.compareTo(BigDecimal.valueOf(cap)) > 0;
        return new DepositAssessment(multiplier, capCheckable, exceeded, true, cap,
            form.get().name());
    }

    /**
     * How many months' rent the deposit is. Kept to two places because it is a snapshot of an
     * agreed figure rather than a computed one — a contract says "two months", and 2.00 should read
     * back as the term it was.
     */
    private static BigDecimal multiplierOf(BigDecimal amount, BigDecimal rentAtCharge) {
        if (rentAtCharge == null || rentAtCharge.signum() <= 0) {
            // Null, not zero. There is no multiple of nothing, and a stored zero is
            // indistinguishable from a computed one to every later reader — including valorization
            // at return, which works from this same base and would compute a valorized deposit of
            // nothing.
            return null;
        }
        return amount.divide(rentAtCharge, 2, RoundingMode.HALF_UP);
    }
}
