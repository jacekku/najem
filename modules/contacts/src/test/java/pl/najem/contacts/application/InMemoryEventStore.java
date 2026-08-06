package pl.najem.contacts.application;

import pl.najem.contracts.events.IntegrationEvent;
import pl.najem.eventstore.ConcurrencyException;
import pl.najem.eventstore.EventStore;
import pl.najem.eventstore.StreamEvents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An event store in a map, partitioned by (stream id, stream type) as the real one is.
 *
 * <p>A third copy of this idea, after property-management's and accounting's {@code
 * RecordingEventStore}. Each module's test source set is its own, and the three differ in what they
 * model — but if a fourth is ever wanted, the honest move is a double in {@code platform:eventstore}'s
 * fixtures beside the interface, not a fourth copy.
 *
 * <p>Contacts keys every stream on the contact id under the type {@code "Contact"}, and a contact id
 * names a stream in no other module, so the partitioning is not load-bearing here the way it is in
 * PM. It is kept anyway because the port it implements is shared, and a double that answered
 * {@code load} with everything appended would be modelling a store that does not exist.
 *
 * <p>The version check refuses an append below the head, which is what {@code JdbcEventStore}
 * rejects: it writes {@code ++version} per event and turns the duplicate key into a
 * {@link ConcurrencyException}. Above the head the real store inserts at a gap and succeeds; this
 * refuses, because nothing in contacts does it and a silent gap in a fake surfaces later as an
 * unexplained version. Rule 16 still holds — the fake is stricter than production about something
 * production has no caller for, and that is written down rather than left to be found.
 */
public class InMemoryEventStore implements EventStore {

    private record StreamKey(UUID streamId, String streamType) {
    }

    private final Map<StreamKey, List<Object>> streams = new LinkedHashMap<>();
    private final List<IntegrationEvent> outbox = new ArrayList<>();

    @Override
    public void append(UUID streamId, String streamType, long expectedVersion,
                       List<Object> events, List<IntegrationEvent> integrationEvents) {
        var stream = streams.computeIfAbsent(new StreamKey(streamId, streamType),
            key -> new ArrayList<>());
        if (expectedVersion < stream.size()) {
            throw new ConcurrencyException();
        }
        if (expectedVersion > stream.size()) {
            throw new IllegalArgumentException("Appending at version " + expectedVersion
                + " would leave a gap after " + stream.size()
                + "; the real store allows this and no caller in contacts does it");
        }
        stream.addAll(events);
        outbox.addAll(integrationEvents);
    }

    @Override
    public StreamEvents load(UUID streamId, String streamType) {
        var stream = streams.getOrDefault(new StreamKey(streamId, streamType), List.of());
        return new StreamEvents(stream.size(), List.copyOf(stream));
    }

    /** What would have gone to the outbox, in the order it was appended. */
    public List<IntegrationEvent> outbox() {
        return List.copyOf(outbox);
    }
}
