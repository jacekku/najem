package pl.najem.reporting.application;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link CheckpointStore}. Stands in for {@code reporting_checkpoint}.
 *
 * <p>{@code advanceTo} and {@code reset} both upsert, because both statements do — and the
 * distinction is load-bearing rather than incidental. A projection's first batch has no row yet,
 * so an implementation that updated instead of upserting would leave the position at zero and
 * replay all of history on every poll. A {@code Map.put} models that correctly; a
 * {@code computeIfPresent} would be the same bug.
 */
public class InMemoryCheckpoints implements CheckpointStore {

    private final Map<String, Long> positions = new HashMap<>();

    @Override
    public long positionOf(String projectionName) {
        return positions.getOrDefault(projectionName, 0L);
    }

    @Override
    public void advanceTo(String projectionName, long globalSeq) {
        positions.put(projectionName, globalSeq);
    }

    @Override
    public void reset(String projectionName) {
        positions.put(projectionName, 0L);
    }
}
