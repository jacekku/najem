package pl.najem.acc.domain;

import java.util.UUID;

/**
 * Raised when a reversed payment is amended onto another tenancy.
 *
 * <p>The opposite failure to {@link PaymentAlreadyReversedException} and worth its own name: that
 * one refuses to undo something twice, this one refuses to move money that is not there. Amending a
 * reversal would credit a tenancy with funds the bank has taken back — the quietest way this module
 * could lie about what an agency holds.
 */
public class ReversedPaymentHasNothingToMoveException extends RuntimeException {

    public ReversedPaymentHasNothingToMoveException(UUID paymentId) {
        super("payment " + paymentId + " was reversed; there is no money to move");
    }
}
