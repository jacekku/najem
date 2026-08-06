package pl.najem.eventstore;

import pl.najem.contracts.events.IntegrationEvent;

import java.util.List;
import java.util.UUID;

public interface EventStore {

    /**
     * Appends domain events to a stream and integration events to the outbox in one transaction.
     * expectedVersion is the version last seen by the caller (0 for a new stream).
     *
     * <p>{@code List<?>} rather than {@code List<Object>} so that a module which has sealed its own
     * events can hand over a {@code List<WorkspaceEvent>} without widening it at the call site.
     * Usermanagement is the first to do so; the others still pass {@code List<Object>}, and both
     * compile against this signature, which is the point of taking it in this order.
     *
     * <p>TODO: narrow to {@code List<? extends DomainEvent>}, with an empty marker interface in this
     * package that every module's events implement. That is what actually stops a {@code String}
     * being appended — {@code List<?>} is looser typing wearing a nicer hat, and it accepts anything
     * {@code List<Object>} did.
     *
     * <p>Deferred rather than dropped, because it is not free: the marker would be the first non-JDK
     * import in any module's {@code domain} package, and architecture rule A3 says there are none —
     * currently true across all four modules with zero exceptions. The counter-argument is that an
     * empty marker drives nothing, which is what A3 is actually about. Somebody has to rule on the
     * letter versus the spirit before this moves.
     */
    void append(UUID streamId, String streamType, long expectedVersion,
                List<?> events, List<IntegrationEvent> integrationEvents);

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
