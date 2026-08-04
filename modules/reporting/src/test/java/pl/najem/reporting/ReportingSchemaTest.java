package pl.najem.reporting;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ReportingSchemaTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/reporting").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void checkpointsStartAtZeroSoAProjectionThatHasNeverRunReplaysEverything() {
        jdbc.update("insert into reporting_checkpoint(projection_name) values ('never-run')");

        assertThat(jdbc.queryForObject(
            "select last_global_seq from reporting_checkpoint where projection_name = 'never-run'", Long.class))
            .isZero();
    }

    /**
     * Replay is the property the whole read side rests on — a projection is rebuilt by resetting
     * its checkpoint, which re-applies events it has already seen. If re-applying could duplicate
     * a timeline row, a rebuild would corrupt the story instead of restoring it.
     */
    @Test
    void reApplyingAnEventCannotDuplicateATimelineEntry() {
        var subject = UUID.randomUUID();
        insertEntry(subject, 1000L);

        assertThatThrownBy(() -> insertEntry(subject, 1000L)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void theSameEventCanTellADifferentStoryAtADifferentLevel() {
        var subject = UUID.randomUUID();
        insertEntry(subject, 2000L);

        jdbc.update("""
            insert into reporting_timeline_entry(workspace_id, level, subject_id, occurred_on, global_seq, kind, summary)
            values (?, 'unit', ?, ?, ?, 'tenancy-activated', 'Tenancy started')
            """, UUID.randomUUID(), subject, LocalDate.of(2026, 8, 4), 2000L);

        assertThat(jdbc.queryForObject(
            "select count(*) from reporting_timeline_entry where subject_id = ?", Integer.class, subject))
            .isEqualTo(2);
    }

    private static void insertEntry(UUID subject, long globalSeq) {
        jdbc.update("""
            insert into reporting_timeline_entry(workspace_id, level, subject_id, occurred_on, global_seq, kind, summary)
            values (?, 'tenancy', ?, ?, ?, 'tenancy-activated', 'Tenancy started')
            """, UUID.randomUUID(), subject, LocalDate.of(2026, 8, 4), globalSeq);
    }
}
