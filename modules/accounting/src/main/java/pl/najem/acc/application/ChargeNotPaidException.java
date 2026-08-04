package pl.najem.acc.application;

/** Raised when an unpaid charge is credited: the correction for unpaid money is deactivation. */
public class ChargeNotPaidException extends RuntimeException {

    public ChargeNotPaidException(String message) {
        super(message);
    }
}
