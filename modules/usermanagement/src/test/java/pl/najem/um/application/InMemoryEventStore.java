package pl.najem.um.application;

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
 * <p><b>The fourth copy of this idea</b>, after property-management's, accounting's and contacts'.
 * The third one's javadoc said that if a fourth was ever wanted, the honest move was a double in
 * {@code platform:eventstore}'s fixtures beside the interface rather than another copy. A fourth is
 * now wanted, so that note has come due — it is left here rather than acted on because moving it is
 * a change to four modules' test wiring and does not belong inside this one. This is a copy that
 * says it is one.
 *
 * <p>Usermanagement runs two stream types, {@code "Workspace"} and {@code "User"}, so the
 * partitioning is load-bearing here: a double that answered {@code load} with everything appended
 * would hand a user's events to a workspace and fail the cast in {@code UmStreams}.
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
                       List<?> events, List<IntegrationEvent> integrationEvents) {
        var stream = streams.computeIfAbsent(new StreamKey(streamId, streamType),
            key -> new ArrayList<>());
        if (expectedVersion < stream.size()) {
            throw new ConcurrencyException();
        }
        if (expectedVersion > stream.size()) {
            throw new IllegalArgumentException("Appending at version " + expectedVersion
                + " would leave a gap after " + stream.size()
                + "; the real store allows this and no caller in usermanagement does it");
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
