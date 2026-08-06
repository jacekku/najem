package pl.najem.um.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.adapter.persistence.PostgresUserManagement;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class WorkspaceServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);

    static JdbcTemplate jdbc;
    static UserService users;
    static WorkspaceService service;
    static WorkspaceAccess access;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        registry.register(WorkspaceCreatedEvent.class);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        users = PostgresUserManagement.userService(store, jdbc);
        service = PostgresUserManagement.workspaceService(store, jdbc);
        access = PostgresUserManagement.workspaceAccess(jdbc);
    }

    @Test
    void createProjectsTheWorkspaceAndPublishesTheIntegrationEvent() {
        var creator = users.register(UUID.randomUUID(), TODAY);

        var workspaceId = service.create("Agencja Krakowska", creator, TODAY);

        assertThat(jdbc.queryForObject("select name from um_workspace where workspace_id = ?",
            String.class, workspaceId)).isEqualTo("Agencja Krakowska");
        var payload = jdbc.queryForObject(
            "select payload::text from outbox where event_type = 'WorkspaceCreatedEvent' and payload->>'workspaceId' = ?",
            String.class, workspaceId.toString());
        assertThat(payload).contains("Agencja Krakowska");
    }

    @Test
    void theCreatorBecomesTheFirstAdmin() {
        var subject = UUID.randomUUID();
        var creator = users.register(subject, TODAY);

        var workspaceId = service.create("Agencja Zalozycielska", creator, TODAY);

        assertThat(access.roleIn(subject, workspaceId)).contains(Role.ADMIN);
    }

    @Test
    void aWorkspaceIsNeverCreatedWithoutAnAdmin() {
        var unknownUser = UUID.randomUUID();

        assertThatThrownBy(() -> service.create("Agencja Bez Admina", unknownUser, TODAY))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from um_workspace where name = ?",
            Integer.class, "Agencja Bez Admina")).isZero();
    }

    @Test
    void renameUpdatesTheProjectionWithoutRepublishing() {
        var creator = users.register(UUID.randomUUID(), TODAY);
        var workspaceId = service.create("Stara Agencja", creator, TODAY);
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox", Integer.class);

        service.rename(workspaceId, "Nowa Agencja", LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject("select name from um_workspace where workspace_id = ?",
            String.class, workspaceId)).isEqualTo("Nowa Agencja");
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(outboxBefore);
    }
}
