package pl.najem.acc.application;

/** Raised when a paid charge is deactivated: the correction for paid money is a credit note. */
public class ChargeAlreadyPaidException extends RuntimeException {

    public ChargeAlreadyPaidException(String message) {
        super(message);
    }
}
