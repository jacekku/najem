package pl.najem.acc.application;

import pl.najem.acc.domain.ArrearsStanding;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link ArrearsStandingProjection} kept in a map.
 *
 * <p>It counts how many times each tenancy has been written, because "the board was refreshed"
 * and "the board says red" are different claims and the second one hides the first: an upsert that
 * writes the colour it already held is invisible in the stored value and visible here.
 */
public class InMemoryArrearsStandingProjection implements ArrearsStandingProjection {

    private record Key(UUID workspaceId, UUID tenancyId) {}

    private final Map<Key, ArrearsStanding> standings = new HashMap<>();
    private final Map<Key, Integer> writes = new HashMap<>();

    public ArrearsStanding find(UUID workspaceId, UUID tenancyId) {
        return standings.get(new Key(workspaceId, tenancyId));
    }

    public int writeCount(UUID workspaceId, UUID tenancyId) {
        return writes.getOrDefault(new Key(workspaceId, tenancyId), 0);
    }

    @Override
    public void save(UUID workspaceId, UUID tenancyId, ArrearsStanding standing) {
        var key = new Key(workspaceId, tenancyId);
        standings.put(key, standing);
        writes.merge(key, 1, Integer::sum);
    }
}
