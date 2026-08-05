package pl.najem.acc.application;

/** Raised when a paid invoice is withdrawn: the correction for paid money is a credit note. */
public class InvoiceAlreadyPaidException extends RuntimeException {

    public InvoiceAlreadyPaidException(String message) {
        super(message);
    }
}
