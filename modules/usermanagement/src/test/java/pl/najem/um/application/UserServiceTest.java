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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.adapter.persistence.PostgresUserManagement;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class UserServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static UserService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        service = PostgresUserManagement.userService(
            new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry), jdbc);
    }

    @Test
    void registrationIsLookupableBySubject() {
        var subject = UUID.randomUUID();

        var userId = service.register(subject, LocalDate.of(2026, 8, 3));

        assertThat(service.findBySubject(subject)).contains(userId);
    }

    @Test
    void registrationStoresNoPersonalData() {
        var subject = UUID.randomUUID();
        var userId = service.register(subject, LocalDate.of(2026, 8, 3));

        var payloads = jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, userId);
        assertThat(payloads).isNotEmpty()
            .allSatisfy(p -> assertThat(p).doesNotContain("@"));
    }

    @Test
    void contactLinkIsStoredOnTheUser() {
        var contactId = UUID.randomUUID();
        var userId = service.register(UUID.randomUUID(), LocalDate.of(2026, 8, 3));

        service.linkContact(userId, contactId, LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject("select contact_id from um_user where user_id = ?",
            UUID.class, userId)).isEqualTo(contactId);
    }
}
