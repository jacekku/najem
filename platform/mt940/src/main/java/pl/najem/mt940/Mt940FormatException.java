package pl.najem.mt940;

/** Every rejection this library makes. The message always quotes the text that caused it. */
public class Mt940FormatException extends RuntimeException {

    public Mt940FormatException(String message) {
        super(message);
    }
}
