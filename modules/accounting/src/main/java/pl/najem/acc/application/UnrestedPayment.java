package pl.najem.acc.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A payment still holding money nobody has placed, as the bank described it.
 *
 * <p>The bank's own words — title, counterparty, direction, currency — because the human deciding
 * where this belongs is reading them, and a queue that showed only an amount would be asking them
 * to guess.
 *
 * <p>How long it has waited and what that means are deliberately absent. Those are
 * {@link SuspenseEntry}'s, computed against a date and a pair of thresholds that belong to
 * {@link SuspenseService} — an ageing band is policy, and policy must not vary with the store.
 */
public record UnrestedPayment(UUID paymentId, String externalId, BigDecimal amount, String title,
                              String counterpartyName, String direction, String currency,
                              LocalDate bookingDate) {
}
