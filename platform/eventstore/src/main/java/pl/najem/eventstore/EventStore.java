package pl.najem.eventstore;

import pl.najem.contracts.events.IntegrationEvent;

import java.util.List;
import java.util.UUID;

public interface EventStore {

    /**
     * Appends domain events to a stream and integration events to the outbox in one transaction.
     * expectedVersion is the version last seen by the caller (0 for a new stream).
     */
    void append(UUID streamId, String streamType, long expectedVersion,
                List<Object> events, List<IntegrationEvent> integrationEvents);

    StreamEvents load(UUID streamId);
}
