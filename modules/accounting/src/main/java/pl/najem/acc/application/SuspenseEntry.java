package pl.najem.acc.application;

import pl.najem.acc.domain.SuspenseAge;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of money that has not come to rest, and how long it has been waiting.
 *
 * @param amount what is still unresolved — for a partly allocated payment that is the remainder,
 *               not the whole transfer
 */
public record SuspenseEntry(UUID paymentId, String externalId, BigDecimal amount, String title,
                            String counterpartyName, String direction, String currency,
                            LocalDate bookingDate, int daysWaiting, SuspenseAge age) {
}
