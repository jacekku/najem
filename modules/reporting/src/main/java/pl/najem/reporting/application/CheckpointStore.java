package pl.najem.reporting.application;

/**
 * Where each projection has got to in the shared stream.
 * <p>
 * A {@code Repository} rather than a {@code Projection} (rule A7), and it is the one table in this
 * module that earns the suffix: {@code reporting_checkpoint} cannot be rebuilt from anything.
 * Every other table Reporting owns is derived from the event store and dropping it costs a replay;
 * dropping this one loses the position that says what still needs replaying. It is a record.
 * <p>
 * <b>The caller owns the transaction, not this port.</b> {@link ProjectionRunner} applies a batch
 * and advances the position inside one transaction, because a crash between the two replays events
 * a projection has already counted. That boundary is the runner's decision and it stays there —
 * an implementation that opened its own transaction per call would quietly break the guarantee
 * while every method still did what its name says.
 */
public interface CheckpointStore {

    /** How far {@code projectionName} has got, or 0 if it has never run. Never null. */
    long positionOf(String projectionName);

    /**
     * Moves {@code projectionName} to {@code globalSeq}, inserting the row if it is the first run.
     * <p>
     * An upsert rather than an update, and the difference is worse than it sounds. The first batch
     * a new projection drains has no row yet, so an update matching nothing leaves the position at
     * zero — and because {@link ProjectionRunner} drains until a batch comes back empty, the very
     * next read returns the same batch again and the drain loop never terminates. Not "replays on
     * every poll": spins, on the first poll, forever. An implementation was mutated to prove it,
     * and it took the test JVM out rather than failing an assertion.
     */
    void advanceTo(String projectionName, long globalSeq);

    /** Sends {@code projectionName} back to the start of history, for a rebuild. */
    void reset(String projectionName);
}
