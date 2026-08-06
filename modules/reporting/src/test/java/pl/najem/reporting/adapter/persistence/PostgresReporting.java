package pl.najem.reporting.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import pl.najem.reporting.application.Projection;
import pl.najem.reporting.application.ProjectionRunner;

import java.util.List;

/**
 * Assembles the projection runner onto a database, for the container tests that do not boot Spring.
 *
 * <p>The counterpart of {@code PostgresAccounting} and {@code PostgresUserManagement}, written for
 * the same reason (rule 6): which implementation of a port to use is knowledge that belongs on the
 * adapter side of the boundary, not in a convenience constructor in the application layer.
 *
 * <p>Unlike those two this lives in {@code src/test} rather than {@code src/testFixtures}, because
 * nothing outside this module wires Reporting — Reporting depends on no module and no module
 * depends on it. Promoting it to a fixtures source set would add a Gradle plugin to lend it to
 * nobody. Move it if that ever stops being true.
 */
public final class PostgresReporting {

    private PostgresReporting() {}

    public static ProjectionRunner runner(JdbcTemplate jdbc, ObjectMapper json,
                                          TransactionTemplate tx, List<Projection> projections,
                                          int batchSize) {
        return new ProjectionRunner(new PostgresEventFeed(jdbc, json),
            new PostgresCheckpointStore(jdbc), tx, projections, batchSize);
    }
}
