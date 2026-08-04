package pl.najem.pm.domain;

import java.time.LocalDate;

/**
 * Ending a tenancy. The two dates are genuinely different facts and Accounting needs both:
 * endDate is when the agreement stopped, vacateDate is when the keys actually came back, and
 * the deposit-settlement clock runs from the later of vacating and the move-out protocol.
 *
 * <p>vacateDate is null when nobody ever moved in (ERROR_ANNULLED) or has not moved out yet.
 * backToMarket false leaves the unit closed — the manager may be renovating, selling, or
 * housing a family member, and PM does not guess which.
 */
public record EndTenancy(LocalDate endDate, LocalDate vacateDate, EndReason reason,
                         String comment, boolean backToMarket) {
}
