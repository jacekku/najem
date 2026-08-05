package pl.najem.reporting;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspace is the hard tenancy boundary, so this asserts it structurally rather than trusting that
 * every query remembered to filter: every reporting table carries {@code workspace_id}, and every
 * one of those has it as an index's <em>leading</em> column.
 * <p>
 * The index half is the one that is easy to omit and expensive to discover — a scoping predicate on
 * an unindexed column is a sequential scan per query, and it degrades silently as data grows rather
 * than failing. Asked for by najem-reviewer (najem-build seq 187); the column half generalises
 * {@code contacts/SchemaTest}.
 */
@Testcontainers
@Tag("integration")
class ReportingWorkspaceScopingTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    /**
     * Tables that legitimately hold no workspace, each with the reason it is exempt.
     * <p>
     * Enumerated rather than inferred, because adding a table is a deliberate migration and a new
     * one should have to argue its way onto this list. An exemption without a reason here is a
     * scoping bug that looks like a decision.
     */
    private static final List<String> NOT_WORKSPACE_SCOPED = List.of(
        // Projector position. A checkpoint is per projection across all workspaces — the projector
        // reads one global event sequence, so a per-workspace cursor would be meaningless.
        "reporting_checkpoint",
        // Derivation indexes existing only because accounting's events carry no workspaceId
        // (najem-build seq 88, ruled at 91). They map an id to an id and are deleted when that
        // field lands — scoping them would outlive their reason to exist.
        "reporting_charge_index",
        "reporting_payment_index",
        // The flyway bookkeeping table, which is not ours.
        "flyway_schema_history");

    /**
     * Tables that carry {@code workspace_id} but are never <em>filtered</em> by it, so a
     * workspace-leading index would serve no query.
     * <p>
     * {@code reporting_tenancy_index} is read only as {@code where tenancy_id = ?} — the workspace
     * is the value being looked up, not the predicate. Indexing it first would be an index nothing
     * uses. If a query ever filters this table by workspace, remove the exemption.
     */
    private static final List<String> WORKSPACE_IS_LOOKED_UP_NOT_FILTERED = List.of(
        "reporting_tenancy_index");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/reporting").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void everyReportingTableCarriesAWorkspaceOrIsAnExplicitException() {
        var unscoped = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public' and table_name like 'reporting%'
              and table_name not in (select table_name from information_schema.columns
                                     where table_schema = 'public' and column_name = 'workspace_id')
            """, String.class);

        assertThat(unscoped)
            .as("""
                A reporting table with no workspace_id and no entry in NOT_WORKSPACE_SCOPED. \
                Workspace is the tenancy boundary: either scope the table, or add it to that list \
                with the reason it cannot be scoped.""")
            .allSatisfy(table -> assertThat(NOT_WORKSPACE_SCOPED).contains(table));
    }

    /**
     * A scoping predicate on an unindexed column is a sequential scan. It never fails — it just
     * gets slower as an agency's data grows, which is the failure mode nobody notices in a test.
     */
    @Test
    void everyWorkspaceScopedTableIndexesWorkspaceFirst() {
        var scopedTables = jdbc.queryForList("""
            select table_name from information_schema.columns
            where table_schema = 'public' and column_name = 'workspace_id'
              and table_name like 'reporting%'
            """, String.class).stream()
            .filter(table -> !WORKSPACE_IS_LOOKED_UP_NOT_FILTERED.contains(table))
            .toList();

        assertThat(scopedTables).isNotEmpty();
        assertThat(scopedTables).allSatisfy(table -> {
            var leadingColumns = jdbc.queryForList("""
                select a.attname
                from pg_index i
                join pg_class c on c.oid = i.indrelid
                join pg_attribute a on a.attrelid = c.oid and a.attnum = i.indkey[0]
                where c.relname = ?
                """, String.class, table);

            assertThat(leadingColumns)
                .as("""
                    %s has workspace_id but no index leading with it, so every workspace-scoped \
                    query against it is a sequential scan.""".formatted(table))
                .contains("workspace_id");
        });
    }

    /**
     * Reporting holds no personal data: it renders identifiers and derived facts, and a person's
     * details live only in the contacts lookaside so an erasure is one row.
     * <p>
     * The column list matches {@code contacts/SchemaTest} exactly. A property's address is
     * deliberately NOT on it — it is a fact about a building, held by PM already, and Reporting
     * needs it to label a board. Widening this list to "anything that looks identifying" would
     * make the test fail on data it has no business objecting to.
     */
    @Test
    void holdsNoPersonalData() {
        assertThat(jdbc.queryForList("""
            select table_name || '.' || column_name from information_schema.columns
            where table_schema = 'public' and table_name like 'reporting%'
              and column_name in ('given_name', 'surname', 'email', 'phone')
            """, String.class))
            .as("personal data belongs in the contacts lookaside, never in a projection")
            .isEmpty();
    }
}
