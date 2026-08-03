package pl.najem.contacts.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.ContactsEventTypes;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ContactServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final UUID AGENCY = UUID.randomUUID();

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContactService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        service = new ContactService(store, jdbc, new RetentionService(store, jdbc));
    }

    @Test
    void storesPersonalDataInTheLookasideAndOnlyIdentifiersInTheEvent() {
        var anna = new NewContact(AGENCY,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), LocalDate.of(2027, 8, 3));

        var contactId = service.register(anna);

        assertThat(store.load(contactId).events()).containsExactly(
            new ContactRegistered(AGENCY, contactId, "legitimate-interest",
                LocalDate.of(2026, 8, 3), LocalDate.of(2027, 8, 3)));
        assertThat(jdbc.queryForMap("select * from contacts_person where contact_id = ?", contactId))
            .containsEntry("workspace_id", AGENCY)
            .containsEntry("given_name", "Anna")
            .containsEntry("surname", "Kowalska")
            .containsEntry("email", "anna@example.com")
            .containsEntry("phone", "+48600100200");
    }

    @Test
    void keepsPersonalDataOutOfTheEventStorePayload() {
        var contactId = service.register(new NewContact(AGENCY,
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", "+48600300400"),
            "contract", LocalDate.of(2026, 8, 3), null));

        var payloads = jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, contactId);

        assertThat(payloads).isNotEmpty();
        assertThat(payloads).noneSatisfy(payload ->
            assertThat(payload).containsAnyOf("Piotr", "Nowak", "piotr@example.com", "+48600300400"));
    }
}
