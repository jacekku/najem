package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * In-memory {@link EventFeed}. Stands in for the {@code events} table.
 *
 * <p><b>It models the mechanism, not the outcome</b> (rule 14). The real feed filters by
 * {@code stream_type in (allowlist)} in SQL, orders by {@code global_seq}, and applies the limit
 * <em>after</em> the filter; this does the same three things in the same order against a list. The
 * ordering matters more than it looks: filtering after the limit would let a run of forbidden
 * events return an empty batch while the store still had allowed events behind them, and a fake
 * that got that order wrong would agree with the real one on every test that has no private stream
 * in it — which is most of them.
 *
 * <p>It reads {@link EventFeed#ALLOWED_STREAMS} rather than carrying its own copy, on purpose. A
 * second copy would let the two drift and this would keep passing; and what the fast tier is here
 * to assert is that the runner never receives a forbidden event, not that this class can hold a
 * set of strings.
 *
 * <p>What it does NOT model: gaps in {@code global_seq}, jsonb parsing, or the transaction the
 * runner wraps a batch in. Those are the container tier's to prove — see the header on
 * {@link ProjectionRunnerRulesTest} and rule 16 for which tier wins when they disagree.
 */
public class InMemoryEventFeed implements EventFeed {

    private final List<FeedEntry> stored = new ArrayList<>();
    private long nextSeq = 1;

    /** Appends an event on a stream Reporting is allowed to read, and returns its global seq. */
    public long append(String streamType, String eventType) {
        return appendAt(nextSeq++, streamType, eventType);
    }

    /**
     * Appends at an explicit sequence number, so a test can leave a gap the way a rolled-back
     * transaction does — the sequence is burned and the number never arrives.
     */
    public long appendAt(long globalSeq, String streamType, String eventType) {
        stored.add(new FeedEntry(globalSeq, UUID.randomUUID(), streamType, eventType,
            Instant.EPOCH, JsonNodeFactory.instance.objectNode()));
        nextSeq = Math.max(nextSeq, globalSeq + 1);
        return globalSeq;
    }

    @Override
    public List<FeedEntry> since(long afterGlobalSeq, int limit) {
        return stored.stream()
            .filter(e -> e.globalSeq() > afterGlobalSeq)
            .filter(e -> ALLOWED_STREAMS.contains(e.streamType()))
            .sorted(Comparator.comparingLong(FeedEntry::globalSeq))
            .limit(limit)
            .toList();
    }
}
