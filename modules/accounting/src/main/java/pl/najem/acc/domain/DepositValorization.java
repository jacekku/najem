package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a deposit is worth on the day it goes back.
 *
 * <p>Art. 6 ust. 4 ustawy o ochronie praw lokatorów: the deposit is returned in the amount
 * corresponding to the agreed multiple of the czynsz <em>in force on the day of return</em>, but
 * never less than the sum actually taken. So the tenant carries none of the inflation risk and the
 * landlord carries none of the deflation risk — the floor is one-directional on purpose.
 *
 * <p>The multiple comes from the snapshot taken at activation, not from dividing today's figures:
 * the rent moves over a tenancy and the agreed multiple does not, so recomputing it would quietly
 * re-price the contract every time rent changed.
 *
 * @param valorized    what goes back to the tenant before any deductions
 * @param floorApplied whether the nominal floor was what decided it — rent fell, or never rose
 */
public record DepositValorization(BigDecimal valorized, boolean floorApplied) {

    /**
     * @param multiplier   months of czynsz, as agreed and snapshotted at activation
     * @param nominal      what was actually taken, which is the floor
     * @param rentAtReturn the czynsz in force on the day of return
     */
    public static DepositValorization compute(BigDecimal multiplier, BigDecimal nominal,
                                              BigDecimal rentAtReturn) {
        if (nominal == null || nominal.signum() <= 0) {
            throw new IllegalArgumentException("a deposit that was never taken cannot be returned");
        }
        if (multiplier == null || multiplier.signum() <= 0 || rentAtReturn == null
            || rentAtReturn.signum() <= 0) {
            return new DepositValorization(nominal.setScale(2, RoundingMode.HALF_UP), true);
        }
        BigDecimal atCurrentRent = multiplier.multiply(rentAtReturn).setScale(2, RoundingMode.HALF_UP);
        BigDecimal floor = nominal.setScale(2, RoundingMode.HALF_UP);
        return atCurrentRent.compareTo(floor) > 0
            ? new DepositValorization(atCurrentRent, false)
            : new DepositValorization(floor, true);
    }
}
