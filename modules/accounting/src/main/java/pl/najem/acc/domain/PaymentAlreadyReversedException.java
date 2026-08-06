package pl.najem.acc.domain;

import java.util.UUID;

/**
 * Raised when a payment the bank already took back is reversed again.
 *
 * <p>Not an {@link IllegalStateException}. That says a caller phoned at the wrong moment; this says
 * a specific thing about money — reversing twice would reopen the tenant's charges twice and owe
 * them a sum that never left anyone's account. A caller that wants to treat "already done" as
 * success can catch this and cannot catch the other without catching every misuse in the module.
 */
public class PaymentAlreadyReversedException extends RuntimeException {

    public PaymentAlreadyReversedException(UUID paymentId) {
        super("payment " + paymentId + " is already reversed");
    }
}
