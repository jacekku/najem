package pl.najem.eventstore;

import java.util.List;

/** version = last stored version of the stream, 0 if the stream is empty. */
public record StreamEvents(long version, List<Object> events) {
}
