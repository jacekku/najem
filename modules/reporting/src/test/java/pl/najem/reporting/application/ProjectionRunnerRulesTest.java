package pl.najem.reporting.application;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fast mirror of {@link ProjectionRunnerTest}, over in-memory doubles.
 *
 * <p><b>When the two disagree, the database is right and this file is wrong</b> (rule 16). This
 * tier exists because the runner's logic — the batch loop, the fetched-versus-applied distinction,
 * per-projection positions, rebuild-then-drain — is ordinary branching that had no way to be
 * exercised without a container, so every change to it cost two minutes to check. It now costs
 * seconds. That is the whole claim; it is not a claim to have replaced the container tier.
 *
 * <p><b>Three things here are deliberately not modelled, and each is somebody else's tier:</b>
 * <ul>
 *   <li><b>The transaction.</b> {@link #NO_TRANSACTIONS} runs the callback and commits nothing.
 *       The guarantee that a batch's applies and its checkpoint advance atomically is
 *       {@code ProjectionRunnerTest}'s to prove, and a green run here says nothing about it.</li>
 *   <li><b>The SQL.</b> The allowlist is enforced in a {@code where} clause by the real feed and by
 *       a {@code filter} here. {@link EventFeedTest} is what proves the clause matches the set.</li>
 *   <li><b>jsonb.</b> Payloads here are empty objects; nothing in the runner reads one.</li>
 * </ul>
 */
class ProjectionRunnerRulesTest {

    /**
     * A transaction manager that starts and commits nothing.
     *
     * <p>Named for what it does rather than what it is, because the name is the warning: a reader
     * who sees {@code tx} in this file and assumes atomicity is being tested has drawn exactly the
     * wrong conclusion.
     */
    private static final PlatformTransactionManager NO_TRANSACTIONS = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    private final InMemoryEventFeed feed = new InMemoryEventFeed();
    private final InMemoryCheckpoints checkpoints = new InMemoryCheckpoints();

    /** Records what it was handed, so the tests assert on delivery rather than on side effects. */
    static class RecordingProjection implements Projection {
        private final String name;
        private final Set<String> handles;
        final List<Long> applied = new ArrayList<>();
        boolean wasReset;

        RecordingProjection(String name, Set<String> handles) {
            this.name = name;
            this.handles = handles;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Set<String> handles() {
            return handles;
        }

        @Override
        public void apply(FeedEntry entry) {
            applied.add(entry.globalSeq());
        }

        @Override
        public void reset() {
            wasReset = true;
            applied.clear();
        }
    }

    private ProjectionRunner runnerFor(int batchSize, Projection... projections) {
        return new ProjectionRunner(feed, checkpoints, new TransactionTemplate(NO_TRANSACTIONS),
            List.of(projections), batchSize);
    }

    private ProjectionRunner runnerFor(Projection... projections) {
        return runnerFor(100, projections);
    }

    @Test
    void deliversOnlyTheEventTypesAProjectionDeclares() {
        long reserved = feed.append("Tenancy", "TenancyReserved");
        feed.append("Tenancy", "TenancyActivated");
        long applied = feed.append("Tenancy", "RentChangeApplied");
        var projection = new RecordingProjection("interested", Set.of("TenancyReserved", "RentChangeApplied"));

        runnerFor(projection).runOnce();

        assertThat(projection.applied).containsExactly(reserved, applied);
    }

    @Test
    void appliesEachEventExactlyOnceAcrossRuns() {
        long first = feed.append("Tenancy", "TenancyReserved");
        long second = feed.append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("once", Set.of("TenancyReserved"));
        var runner = runnerFor(projection);

        assertThat(runner.runOnce()).isEqualTo(2);
        assertThat(runner.runOnce()).isZero();

        assertThat(projection.applied).containsExactly(first, second);
    }

    /**
     * The first batch a projection ever drains has no checkpoint row. If the position were written
     * with an update rather than an upsert it would stay at zero, and the projection would replay
     * all of history on every poll while still looking like it was making progress.
     */
    @Test
    void aProjectionsFirstBatchLeavesAPositionBehindIt() {
        long only = feed.append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("fresh", Set.of("TenancyReserved"));

        runnerFor(projection).runOnce();

        assertThat(checkpoints.positionOf("fresh")).isEqualTo(only);
    }

    @Test
    void picksUpWhereItLeftOffWhenNewEventsArrive() {
        feed.append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("resumes", Set.of("TenancyReserved"));
        var runner = runnerFor(projection);
        runner.runOnce();

        long later = feed.append("Tenancy", "TenancyReserved");

        assertThat(runner.runOnce()).isEqualTo(1);
        assertThat(projection.applied).endsWith(later);
    }

    @Test
    void rebuildResetsTheProjectionAndReplaysHistoryToTheSameResult() {
        long first = feed.append("Tenancy", "TenancyReserved");
        long second = feed.append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("rebuildable", Set.of("TenancyReserved"));
        var runner = runnerFor(projection);
        runner.runOnce();
        var beforeRebuild = List.copyOf(projection.applied);

        runner.rebuild("rebuildable");

        assertThat(projection.wasReset).isTrue();
        assertThat(projection.applied).containsExactly(first, second).isEqualTo(beforeRebuild);
    }

    @Test
    void rebuildingOneProjectionLeavesTheOthersAlone() {
        long only = feed.append("Tenancy", "TenancyReserved");
        var rebuilt = new RecordingProjection("rebuilt", Set.of("TenancyReserved"));
        var untouched = new RecordingProjection("untouched", Set.of("TenancyReserved"));
        var runner = runnerFor(rebuilt, untouched);
        runner.runOnce();

        runner.rebuild("rebuilt");

        assertThat(rebuilt.wasReset).isTrue();
        assertThat(untouched.wasReset).isFalse();
        assertThat(checkpoints.positionOf("untouched")).isEqualTo(only);
    }

    @Test
    void rebuildingAProjectionThatDoesNotExistIsRefusedRatherThanIgnored() {
        var runner = runnerFor(new RecordingProjection("real", Set.of("TenancyReserved")));

        assertThatThrownBy(() -> runner.rebuild("typo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("typo");
    }

    /**
     * A rolled-back transaction burns its sequence number permanently. A projector that waits for
     * the missing seq stops forever and looks idle rather than broken.
     */
    @Test
    void aPermanentGapInTheSequenceDoesNotStallTheRunner() {
        long first = feed.appendAt(1, "Tenancy", "TenancyReserved");
        long afterGap = feed.appendAt(3, "Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("gappy", Set.of("TenancyReserved"));

        runnerFor(projection).runOnce();

        assertThat(projection.applied).containsExactly(first, afterGap);
    }

    @Test
    void drainsMoreThanOneBatch() {
        for (int i = 0; i < 5; i++) {
            feed.append("Tenancy", "TenancyReserved");
        }
        var projection = new RecordingProjection("batched", Set.of("TenancyReserved"));

        assertThat(runnerFor(2, projection).runOnce()).isEqualTo(5);
        assertThat(projection.applied).hasSize(5);
    }

    @Test
    void keepsAnIndependentCheckpointPerProjection() {
        feed.append("Tenancy", "TenancyReserved");
        var early = new RecordingProjection("early", Set.of("TenancyReserved"));
        runnerFor(early).runOnce();

        var late = new RecordingProjection("late", Set.of("TenancyReserved"));
        runnerFor(late).runOnce();

        assertThat(late.applied).hasSize(1);
    }

    /**
     * The allowlist tripwire, and the reason the feed is a port at all.
     *
     * <p>A projection asks for {@code MemberInvited} and would happily render it; the only thing
     * standing between another module's private stream and a Reporting table is the feed. This
     * asserts the negative directly rather than inferring it from a row count.
     */
    @Test
    void aProjectionNeverSeesAnEventFromAStreamOutsideTheAllowlist() {
        feed.append("Invitation", "MemberInvited");
        long allowed = feed.append("Tenancy", "TenancyReserved");
        var greedy = new RecordingProjection("greedy", Set.of("TenancyReserved", "MemberInvited"));

        runnerFor(greedy).runOnce();

        assertThat(greedy.applied).containsExactly(allowed);
    }

    /**
     * The starvation case: a whole batch of events this projection does not handle is still
     * progress, and the loop must continue on what was FETCHED rather than on what was applied.
     *
     * <p>Otherwise a run of uninteresting events longer than one batch parks the projector in front
     * of itself and everything behind them waits forever — silently, because {@code runOnce}
     * returns zero and a stalled projector is indistinguishable from an idle one.
     *
     * <p><b>It is unhandled TYPES here and not forbidden streams, and the first version of this
     * test got that wrong.</b> Forbidden streams cannot starve the runner, because the feed filters
     * them in its {@code where} clause and they never reach this loop at all — so a test built on
     * them passed with the bug in place. Unhandled types are the batch the feed does return.
     * A mutation is what said so (rule 21); the assertion looked identical either way.
     */
    @Test
    void aBatchOfNothingButUnhandledTypesStillLetsTheRunnerReachWhatIsBehindThem() {
        for (int i = 0; i < 5; i++) {
            feed.append("Tenancy", "TenancyActivated");
        }
        long wanted = feed.append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("starved", Set.of("TenancyReserved"));

        runnerFor(2, projection).runOnce();

        assertThat(projection.applied).containsExactly(wanted);
    }

    /**
     * The allowlist, pinned to the names the ruling gives (rule 12: these are wire values — they
     * are matched against {@code stream_type} as written by four other modules, so renaming one is
     * a migration, not a refactor).
     *
     * <p><b>This assertion used to live in {@link EventFeedTest}, which is {@code @Tag("integration")}
     * and excluded from {@code ./gradlew build}.</b> It reads no database, so all that bought was
     * exclusion: narrowing the allowlist, or emptying it, passed the entire inner loop and was
     * caught only by the suite people run at a merge. A cross-module security boundary should fail
     * in the fast tier.
     */
    @Test
    void theAllowlistIsExactlyTheStreamsTheRulingNames() {
        assertThat(EventFeed.ALLOWED_STREAMS)
            .containsExactlyInAnyOrder("Property", "Unit", "Tenancy", "TenancyLedger", "Payment",
                "Contact", "Workspace", "User");
    }

    /**
     * The companion to the tripwire above (rule 20): a check that a forbidden stream is refused is
     * worthless if <em>every</em> stream is refused.
     *
     * <p><b>On its own this does not catch an emptied allowlist</b>, and an earlier version of this
     * comment claimed it did. It iterates {@code ALLOWED_STREAMS} to build its own expectation, so
     * an empty set means an empty expectation and a green test — the mutation that proved it is the
     * reason the pinning assertion above now exists beside it. What this one adds is different and
     * still worth having: that a stream named in the set genuinely reaches a projection, rather
     * than being admitted by the constant and dropped somewhere between the feed and the runner.
     */
    @Test
    void everyAllowlistedStreamActuallyReachesAProjection() {
        var expected = new ArrayList<Long>();
        for (var stream : EventFeed.ALLOWED_STREAMS) {
            expected.add(feed.append(stream, "SomethingHappened"));
        }
        var everything = new RecordingProjection("everything", Set.of("SomethingHappened"));

        runnerFor(everything).runOnce();

        assertThat(everything.applied)
            .as("a stream in ALLOWED_STREAMS that never reaches a projection")
            .containsExactlyInAnyOrderElementsOf(expected);
    }
}
