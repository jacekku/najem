package pl.najem.eventstore;

public class ConcurrencyException extends RuntimeException {

    public ConcurrencyException() {
        super("Stream was modified concurrently; reload and retry");
    }
}
