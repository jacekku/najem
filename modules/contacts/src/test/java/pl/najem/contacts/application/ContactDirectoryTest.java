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
import pl.najem.contacts.domain.ContactDetailsCorrected;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ContactDirectoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final UUID AGENCY = UUID.randomUUID();
    static final UUID OTHER_AGENCY = UUID.randomUUID();

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContactService service;
    static ContactDirectory directory;

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
        directory = new ContactDirectory(jdbc);
    }

    private static UUID anna(UUID workspaceId) {
        return service.register(new NewContact(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));
    }

    @Test
    void resolvesRegisteredContactById() {
        var contactId = anna(AGENCY);

        assertThat(directory.find(AGENCY, contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"));
    }

    @Test
    void returnsEmptyForUnknownContact() {
        assertThat(directory.find(AGENCY, UUID.randomUUID())).isEmpty();
    }

    @Test
    void doesNotResolveAContactBelongingToAnotherWorkspace() {
        var contactId = anna(OTHER_AGENCY);

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    @Test
    void findsCandidatesByEmailWithinTheWorkspaceOnly() {
        var mine = anna(AGENCY);
        anna(OTHER_AGENCY);

        assertThat(directory.findByEmail(AGENCY, "anna@example.com")).contains(mine);
        assertThat(directory.findByEmail(OTHER_AGENCY, "anna@example.com")).doesNotContain(mine);
    }

    @Test
    void correctsDetailsWithoutLeakingThemIntoTheEvent() {
        var contactId = service.register(new NewContact(AGENCY,
            new ContactDetails("Ana", "Kowalsk", "ana@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));

        service.correctDetails(AGENCY, contactId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100201"),
            LocalDate.of(2026, 8, 4));

        assertThat(directory.find(AGENCY, contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100201"));
        assertThat(store.load(contactId, "Contact").events())
            .contains(new ContactDetailsCorrected(AGENCY, contactId, LocalDate.of(2026, 8, 4)));
        assertThat(jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, contactId))
            .noneSatisfy(payload -> assertThat(payload).containsAnyOf("Anna", "Kowalska", "anna@example.com"));
    }

    @Test
    void doesNotCorrectAContactBelongingToAnotherWorkspace() {
        var contactId = anna(OTHER_AGENCY);

        service.correctDetails(AGENCY, contactId,
            new ContactDetails("Wrong", "Person", "wrong@example.com", "+48000000000"),
            LocalDate.of(2026, 8, 4));

        assertThat(directory.find(OTHER_AGENCY, contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"));
    }
}
