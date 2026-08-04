package pl.najem.contacts;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    static JdbcTemplate migrated() {
        if (jdbc == null) {
            var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @Test
    void createsTheFourContactsTables() {
        assertThat(migrated().queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public' and table_name like 'contacts%'
            order by table_name
            """, String.class))
            .containsExactly("contacts_erasure_log", "contacts_interest",
                "contacts_person", "contacts_retention_hold");
    }

    @Test
    void confinesPersonalDataToThePersonTable() {
        assertThat(migrated().queryForList("""
            select table_name || '.' || column_name from information_schema.columns
            where table_schema = 'public' and table_name like 'contacts%'
              and column_name in ('given_name', 'surname', 'email', 'phone')
            """, String.class))
            .isNotEmpty()
            .allSatisfy(column -> assertThat(column).startsWith("contacts_person."));
    }

    /**
     * A scoping predicate on an unindexed column is a sequential scan: it never fails, it just gets
     * slower as an agency's data grows, which is the failure mode no test notices. Asked for by
     * najem-reviewer (najem-build seq 187) as the half the column check alone does not cover.
     */
    @Test
    void indexesWorkspaceFirstOnEveryTableThatFiltersByIt() {
        var jdbc = migrated();
        var tables = jdbc.queryForList("""
            select table_name from information_schema.columns
            where table_schema = 'public' and column_name = 'workspace_id'
              and table_name like 'contacts%'
            """, String.class).stream()
            // The erasure tombstone is written and never read: it records THAT an erasure happened,
            // for audit, and nothing queries it by workspace. It carries the column so the row can
            // be attributed, not so it can be filtered. If anything ever reads it, index it.
            .filter(table -> !table.equals("contacts_erasure_log"))
            .toList();

        assertThat(tables).isNotEmpty();
        assertThat(tables).allSatisfy(table -> assertThat(jdbc.queryForList("""
            select a.attname
            from pg_index i
            join pg_class c on c.oid = i.indrelid
            join pg_attribute a on a.attrelid = c.oid and a.attnum = i.indkey[0]
            where c.relname = ?
            """, String.class, table))
            .as("""
                %s filters by workspace_id but no index leads with it, so every scoped query \
                against it is a sequential scan.""".formatted(table))
            .contains("workspace_id"));
    }

    @Test
    void scopesEveryContactsTableByWorkspace() {
        assertThat(migrated().queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public' and table_name like 'contacts%'
              and table_name not in (select table_name from information_schema.columns
                                     where table_schema = 'public' and column_name = 'workspace_id')
            """, String.class))
            .isEmpty();
    }
}
