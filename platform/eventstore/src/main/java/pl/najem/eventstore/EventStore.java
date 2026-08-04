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

    /**
     * The events of one stream, in order. A stream is identified by id AND type: two bounded
     * contexts may legitimately name a stream after the same subject (PM's {@code Tenancy} and
     * accounting's {@code TenancyLedger} share a tenancy id), and each must see only its own
     * events. Loading by id alone returned both modules' events to whichever asked, which crashed
     * the first aggregate to rehydrate a foreign event -- a module cannot defend against that,
     * because it cannot know the other module exists.
     */
    StreamEvents load(UUID streamId, String streamType);
}
