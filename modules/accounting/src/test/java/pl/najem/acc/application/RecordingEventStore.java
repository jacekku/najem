package pl.najem.acc.application;

import pl.najem.contracts.events.IntegrationEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.eventstore.StreamEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps what was appended so the events can be asserted on without a store behind them.
 *
 * <p>It does not partition by stream: {@code load} answers with everything appended so far, so the
 * version it reports is a count of all events rather than of that stream's. That is enough for the
 * optimistic-locking check the services do — they read a version and hand it straight back — and
 * not enough to test the locking itself, which is the event store's own suite.
 */
public class RecordingEventStore implements EventStore {

    final List<Object> appended = new ArrayList<>();

    /** What has been appended, in order, whatever stream it went to. */
    public List<Object> appended() {
        return List.copyOf(appended);
    }

    @Override
    public void append(UUID streamId, String streamType, long expectedVersion,
                       List<?> events, List<IntegrationEvent> integrationEvents) {
        appended.addAll(events);
    }

    @Override
    public StreamEvents load(UUID streamId, String streamType) {
        return new StreamEvents(appended.size(), List.copyOf(appended));
    }
}
