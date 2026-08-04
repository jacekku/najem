package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The deposit was given back.
 *
 * <p>All four figures are carried, not just the one that moved. "You got back what you put in" and
 * "you got back the valorized amount, which happened to equal what you put in" are the same number
 * and different facts, and only the second can be checked against art. 6 ust. 4 a year later.
 *
 * @param valorized what the deposit was worth on the day of return, before deductions
 * @param deducted  what the landlord took from it against what was owed
 * @param returned  what actually went back to the tenant
 */
public record DepositSettled(UUID depositId, UUID tenancyId, BigDecimal valorized,
                             BigDecimal deducted, BigDecimal returned, BigDecimal rentAtReturn,
                             boolean floorApplied) {
}
