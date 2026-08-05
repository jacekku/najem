package pl.najem.um;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class SchemaTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsUserManagementTables() {
        var tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("um_workspace", "um_user", "um_membership",
            "um_invitation", "um_invitation_recipient");
    }

    @Test
    void scopesMembershipByWorkspaceAndUser() {
        var workspaceId = java.util.UUID.randomUUID();
        var userId = java.util.UUID.randomUUID();
        jdbc.update("insert into um_workspace(workspace_id, name, created_on) values (?,?,current_date)",
            workspaceId, "Agencja Testowa");
        jdbc.update("insert into um_user(user_id, keycloak_subject, registered_on) values (?,?,current_date)",
            userId, java.util.UUID.randomUUID());
        jdbc.update("insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,current_date)",
            workspaceId, userId, "ADMIN");

        assertThat(jdbc.queryForObject(
            "select count(*) from um_membership where workspace_id = ?", Integer.class, workspaceId))
            .isEqualTo(1);
    }
}
