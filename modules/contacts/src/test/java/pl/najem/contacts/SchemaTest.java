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
