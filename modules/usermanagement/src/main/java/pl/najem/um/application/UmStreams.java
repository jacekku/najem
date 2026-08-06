package pl.najem.um.application;

import pl.najem.eventstore.StreamEvents;
import pl.najem.um.domain.events.UserEvent;
import pl.najem.um.domain.events.WorkspaceEvent;

import java.util.List;

/**
 * Narrows a loaded stream to the sealed event type its aggregate rebuilds from.
 *
 * <p>{@code StreamEvents.events()} is a {@code List<Object>} and stays one: 97 call sites across
 * four modules read it, and three of those modules have not sealed their events. So the narrowing
 * happens here, on the way in, rather than by making every other module's aggregates generic in a
 * change that is about this one.
 *
 * <p>The cast is checked, and the failure it would report is worth having. A stream loaded by id
 * <em>and</em> type cannot contain a foreign module's events — see {@code EventStore.load} — so a
 * {@code ClassCastException} here means an event type was appended to this stream and never added
 * to {@link WorkspaceEvent}'s permits clause, which is the one way that clause can be wrong. Loudly
 * is the right way to find that out.
 */
final class UmStreams {

    private UmStreams() {}

    static List<WorkspaceEvent> workspaceEvents(StreamEvents stream) {
        return stream.events().stream().map(WorkspaceEvent.class::cast).toList();
    }

    static List<UserEvent> userEvents(StreamEvents stream) {
        return stream.events().stream().map(UserEvent.class::cast).toList();
    }
}
