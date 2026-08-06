package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import pl.najem.reporting.adapter.persistence.PostgresEventFeed;
import pl.najem.reporting.adapter.persistence.PostgresReporting;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class ProjectionRunnerTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    /**
     * Built the way the APPLICATION builds it. A bare {@code new ObjectMapper()} was a third
     * encoding again — no JavaTimeModule at all — on top of the production/module-test divergence
     * (najem-build seq 119). Reporting parses stored payloads, so its tests have no business
     * reading a shape production never writes.
     */
    private static ObjectMapper productionMapper() {
        return JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    }

    static JdbcTemplate jdbc;
    static EventFeed feed;
    static TransactionTemplate tx;

    /** Records what it was handed, so the tests can assert on delivery rather than on side effects. */
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

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/reporting").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        feed = new PostgresEventFeed(jdbc, productionMapper());
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clearTheStore() {
        jdbc.update("delete from events");
        jdbc.update("delete from reporting_checkpoint");
    }

    private static long append(String streamType, String eventType) {
        return jdbc.queryForObject("""
            insert into events(stream_id, stream_type, version, event_type, payload)
            values (?,?,?,?,cast('{}' as jsonb)) returning global_seq
            """, Long.class, UUID.randomUUID(), streamType, 0L, eventType);
    }

    private static ProjectionRunner runnerFor(Projection... projections) {
        return PostgresReporting.runner(jdbc, productionMapper(), tx, List.of(projections), 100);
    }

    @Test
    void deliversOnlyTheEventTypesAProjectionDeclares() {
        long reserved = append("Tenancy", "TenancyReserved");
        append("Tenancy", "TenancyActivated");
        long applied = append("Tenancy", "RentChangeApplied");
        var projection = new RecordingProjection("interested", Set.of("TenancyReserved", "RentChangeApplied"));

        runnerFor(projection).runOnce();

        assertThat(projection.applied).containsExactly(reserved, applied);
    }

    /**
     * The checkpoint is what makes a restart safe. If it did not advance transactionally with the
     * applies, a crash between the two would replay a batch and a projection would double-count.
     */
    @Test
    void appliesEachEventExactlyOnceAcrossRuns() {
        long first = append("Tenancy", "TenancyReserved");
        long second = append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("once", Set.of("TenancyReserved"));
        var runner = runnerFor(projection);

        assertThat(runner.runOnce()).isEqualTo(2);
        assertThat(runner.runOnce()).isZero();

        assertThat(projection.applied).containsExactly(first, second);
    }

    @Test
    void picksUpWhereItLeftOffWhenNewEventsArrive() {
        append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("resumes", Set.of("TenancyReserved"));
        var runner = runnerFor(projection);
        runner.runOnce();

        long later = append("Tenancy", "TenancyReserved");

        assertThat(runner.runOnce()).isEqualTo(1);
        assertThat(projection.applied).endsWith(later);
    }

    /**
     * Rebuildability is the property that lets a read model change shape without a data migration:
     * reset the checkpoint, replay history, get the same answer.
     */
    @Test
    void rebuildResetsTheProjectionAndReplaysHistoryToTheSameResult() {
        long first = append("Tenancy", "TenancyReserved");
        long second = append("Tenancy", "TenancyReserved");
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
        append("Tenancy", "TenancyReserved");
        var rebuilt = new RecordingProjection("rebuilt", Set.of("TenancyReserved"));
        var untouched = new RecordingProjection("untouched", Set.of("TenancyReserved"));
        var runner = runnerFor(rebuilt, untouched);
        runner.runOnce();

        runner.rebuild("rebuilt");

        assertThat(rebuilt.wasReset).isTrue();
        assertThat(untouched.wasReset).isFalse();
        assertThat(jdbc.queryForObject(
            "select last_global_seq from reporting_checkpoint where projection_name = 'untouched'", Long.class))
            .isPositive();
    }

    /**
     * global_seq gaps are permanent — a rolled-back transaction burns its sequence number and it
     * never arrives. A projector that waits for a missing seq stops forever, silently.
     */
    @Test
    void aPermanentGapInTheSequenceDoesNotStallTheRunner() {
        long first = append("Tenancy", "TenancyReserved");
        long rolledBack = append("Tenancy", "TenancyReserved");
        long afterGap = append("Tenancy", "TenancyReserved");
        jdbc.update("delete from events where global_seq = ?", rolledBack);
        var projection = new RecordingProjection("gappy", Set.of("TenancyReserved"));

        runnerFor(projection).runOnce();

        assertThat(projection.applied).containsExactly(first, afterGap);
    }

    /** A forbidden stream must not park the cursor in front of it and starve everything after. */
    @Test
    void advancesPastEventsOnStreamsItIsNotAllowedToSee() {
        append("Invitation", "MemberInvited");
        long allowed = append("Tenancy", "TenancyReserved");
        var projection = new RecordingProjection("blind", Set.of("TenancyReserved", "MemberInvited"));

        runnerFor(projection).runOnce();

        assertThat(projection.applied).containsExactly(allowed);
    }

    @Test
    void drainsMoreThanOneBatch() {
        for (int i = 0; i < 5; i++) {
            append("Tenancy", "TenancyReserved");
        }
        var projection = new RecordingProjection("batched", Set.of("TenancyReserved"));
        var runner = PostgresReporting.runner(jdbc, productionMapper(), tx, List.of(projection), 2);

        assertThat(runner.runOnce()).isEqualTo(5);
        assertThat(projection.applied).hasSize(5);
    }

    /**
     * Two projections keep independent positions: one falling behind, failing or being rebuilt must
     * not move another's cursor. A single shared checkpoint would couple them invisibly.
     */
    @Test
    void keepsAnIndependentCheckpointPerProjection() {
        append("Tenancy", "TenancyReserved");
        var early = new RecordingProjection("early", Set.of("TenancyReserved"));
        runnerFor(early).runOnce();

        var late = new RecordingProjection("late", Set.of("TenancyReserved"));
        runnerFor(late).runOnce();

        assertThat(late.applied).hasSize(1);
    }

    @Test
    void rebuildingAProjectionThatDoesNotExistIsRefusedRatherThanIgnored() {
        var runner = runnerFor(new RecordingProjection("real", Set.of("TenancyReserved")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.rebuild("typo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("typo");
    }
}
