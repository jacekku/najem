package pl.najem.pm.application;

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
 * An event store in a HashMap, partitioned by stream the way the real one is.
 *
 * <p>Partitioning is the whole point and is not a detail that could be skipped. PM keeps a
 * {@code Property} stream and a {@code Unit} stream, and {@code Unit.from} throws on an event it
 * does not recognise — so a store that answered {@code load} with everything appended, as
 * accounting's {@code RecordingEventStore} does, would hand a Unit its parent's
 * {@code PropertyCreated} and blow up. It also keys on id AND type, because a tenancy id names a
 * stream in both PM and accounting, which is the defect {@link EventStore#load} was changed to
 * prevent.
 *
 * <p>The version check models the mechanism rather than the outcome. {@code JdbcEventStore} writes
 * {@code ++version} per event and turns a {@code DuplicateKeyException} into a
 * {@link ConcurrencyException}, so what it actually rejects is an append reusing a version already
 * stored — an expected version <em>below</em> the head. {@code Unit} leans on exactly that for
 * overlap safety under concurrency; its own javadoc says the in-Java check is not what makes two
 * simultaneous reservations safe, so a double that accepted a stale version would quietly retire
 * the mechanism the rule depends on.
 *
 * <p>An expected version <em>above</em> the head is where the two deliberately differ: the real
 * store hits no collision, inserts at a gap and succeeds. This refuses instead, because nothing in
 * PM does it and a silent gap in a fake surfaces three tests later as an unexplained version. Rule
 * 16 says the database wins when the tiers disagree — this is the fake being stricter than
 * production about something production has no caller for, which is the safe direction, and it is
 * written down rather than left to be discovered.
 *
 * <p>Integration events are kept so a test can assert on the outbox, but nothing here publishes
 * them: the real outbox is drained by a runner this side has no business simulating.
 */
public class InMemoryEventStore implements EventStore {

    private record StreamKey(UUID streamId, String streamType) {}

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
                + "; the real store allows this and no caller in PM does it");
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
