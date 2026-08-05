package pl.najem.acc.application;

/** Raised when an unpaid charge is credited: the correction for unpaid money is deactivation. */
public class InvoiceNotPaidException extends RuntimeException {

    public InvoiceNotPaidException(String message) {
        super(message);
    }
}
