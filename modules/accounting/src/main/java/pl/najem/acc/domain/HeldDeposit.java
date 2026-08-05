package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A deposit as the register holds it, and whether it is money in hand.
 *
 * <p>Two things disqualify it and they fail differently. Already settled means returning it again
 * would pay the tenant twice. Charged but never paid means there is nothing to give back — the
 * agency never received it, and valorizing it would invent funds.
 *
 * <p>Both are asked here rather than read out of a row, so that a second caller cannot forget one.
 */
public record HeldDeposit(UUID depositId, BigDecimal multiplier, BigDecimal nominalAmount,
                          boolean settled, BigDecimal unpaid) {

    /** The tenant has actually parted with the money, so there is something to give back. */
    public boolean isPaid() {
        return unpaid.signum() <= 0;
    }
}
