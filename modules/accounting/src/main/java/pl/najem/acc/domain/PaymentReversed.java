package pl.najem.acc.domain;

import java.util.UUID;

/**
 * The bank took the money back — NSF, a chargeback, a recalled transfer. Every charge this payment
 * settled is owed again, at the due date it always had.
 *
 * <p>Not to be confused with {@link PaymentAllocationAmended}: a reversal says the money was never
 * there, an amendment says it was real and went to the wrong tenant.
 */
public record PaymentReversed(UUID paymentId, String reason) {
}
