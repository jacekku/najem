package pl.najem.reporting.application;

import java.util.Set;

/**
 * A read model built by replaying events. Reporting has no aggregates and no commands — every
 * table it owns is derived, and this is the only way anything gets written.
 */
public interface Projection {

    /** Stable across restarts and renames: it is the key of this projection's checkpoint row. */
    String name();

    /**
     * The event types this projection renders. Everything else is skipped — the Timeline is a
     * story a human reads, not an audit log, and the event store already serves the audit need.
     */
    Set<String> handles();

    void apply(FeedEntry entry);

    /**
     * Deletes everything this projection owns, so a rebuild replays into an empty table rather
     * than onto stale rows of the previous shape.
     * <p>
     * Deliberately not a default no-op: a projection that silently forgot to implement it would
     * make {@code rebuild} appear to succeed while leaving rows no replay can ever correct.
     */
    void reset();
}
