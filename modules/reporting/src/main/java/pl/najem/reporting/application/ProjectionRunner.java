package pl.najem.reporting.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Catch-up projector: each projection keeps its own position in the shared event stream and is
 * driven forward from it.
 * <p>
 * Deliberately not an outbox subscription. The outbox is drained, so it can deliver an event once
 * and never again — whereas resetting a checkpoint to zero replays all of history, which is the
 * property that lets a read model change shape without a data migration. Ordering is
 * {@code global_seq}, the one monotonic sequence spanning every stream, which is exactly what a
 * multi-stream Timeline needs and what per-stream versions cannot give.
 */
@Component
public class ProjectionRunner {

    private final EventFeed feed;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<Projection> projections;
    private final int batchSize;

    /**
     * Takes the transaction MANAGER and builds its own template rather than requiring a
     * {@code TransactionTemplate} bean: Boot does not auto-configure one, and having Reporting
     * publish a bean of that type into a shared context would hand every other module a default
     * they never asked for.
     */
    public ProjectionRunner(EventFeed feed, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                            List<Projection> projections,
                            @Value("${najem.reporting.batch-size:500}") int batchSize) {
        this.feed = feed;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.projections = projections;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${najem.reporting.poll-delay-ms:1000}")
    public void poll() {
        runOnce();
    }

    /** Drives every projection to the end of the stream. Returns how many events were applied. */
    public int runOnce() {
        int applied = 0;
        for (var projection : projections) {
            applied += drain(projection);
        }
        return applied;
    }

    /**
     * Discards a projection's data and replays it from the beginning of history.
     * <p>
     * An unknown name throws rather than doing nothing: a mistyped rebuild that silently succeeds
     * leaves an operator believing they fixed a read model they never touched.
     */
    public void rebuild(String projectionName) {
        var projection = projections.stream()
            .filter(p -> p.name().equals(projectionName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No such projection: " + projectionName));
        tx.executeWithoutResult(status -> {
            projection.reset();
            jdbc.update("""
                insert into reporting_checkpoint(projection_name, last_global_seq) values (?, 0)
                on conflict (projection_name) do update set last_global_seq = 0
                """, projectionName);
        });
        drain(projection);
    }

    /** How far one batch got: {@code fetched} drives the loop, {@code applied} is what the caller asked about. */
    private record Progress(int fetched, int applied) {
    }

    private int drain(Projection projection) {
        int applied = 0;
        for (Progress batch; (batch = applyOneBatch(projection)).fetched() > 0; ) {
            applied += batch.applied();
        }
        return applied;
    }

    /**
     * One batch, applied and checkpointed in a single transaction — a crash between the two would
     * otherwise replay events the projection had already counted.
     * <p>
     * The checkpoint advances to the last event the FEED returned, not to the last event this
     * projection handled. Those differ constantly: unhandled types and events on streams Reporting
     * may not read (see {@link EventFeed}) are skipped, and if they did not move the cursor the
     * projector would re-read the same forbidden tail forever and starve everything behind it.
     */
    private Progress applyOneBatch(Projection projection) {
        return tx.execute(status -> {
            long checkpoint = checkpointOf(projection.name());
            var entries = feed.since(checkpoint, batchSize);
            if (entries.isEmpty()) {
                return new Progress(0, 0);
            }
            int applied = 0;
            for (var entry : entries) {
                if (projection.handles().contains(entry.eventType())) {
                    projection.apply(entry);
                    applied++;
                }
            }
            advanceTo(projection.name(), entries.get(entries.size() - 1).globalSeq());
            // The loop must continue on what was FETCHED, not on what was applied: a batch of
            // entirely unhandled events is still progress, and stopping on it would leave the
            // projection permanently short of the end of the stream.
            return new Progress(entries.size(), applied);
        });
    }

    private long checkpointOf(String projectionName) {
        var found = jdbc.queryForList(
            "select last_global_seq from reporting_checkpoint where projection_name = ?",
            Long.class, projectionName);
        return found.isEmpty() ? 0L : found.get(0);
    }

    private void advanceTo(String projectionName, long globalSeq) {
        jdbc.update("""
            insert into reporting_checkpoint(projection_name, last_global_seq) values (?,?)
            on conflict (projection_name) do update set last_global_seq = excluded.last_global_seq
            """, projectionName, globalSeq);
    }
}
