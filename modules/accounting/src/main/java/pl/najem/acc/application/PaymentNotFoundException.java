package pl.najem.acc.application;

/**
 * Raised when a payment is asked for and is not there.
 *
 * <p>A payment outside the caller's workspace does not exist, rather than being forbidden — the
 * workspace is part of the question, so another agency's payment is simply absent.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
