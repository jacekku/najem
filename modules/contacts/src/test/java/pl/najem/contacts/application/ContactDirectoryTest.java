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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        var contactDirectory = new ContactDirectory(jdbc);
        service = new ContactService(store, jdbc, new RetentionService(store, jdbc, contactDirectory), contactDirectory);
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

    private static UUID person(UUID workspaceId, String given, String surname) {
        return service.register(new NewContact(workspaceId,
            new ContactDetails(given, surname, given.toLowerCase() + "@example.com", null),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));
    }

    @Test
    void searchFindsAPersonByAFragmentOfEitherName() {
        var found = person(AGENCY, "Bogumiła", "Szukalska");

        assertThat(directory.search(AGENCY, "szukal")).extracting(ContactDirectory.Match::contactId)
            .as("surname fragment, case-insensitively")
            .contains(found);
        assertThat(directory.search(AGENCY, "Bogumi")).extracting(ContactDirectory.Match::contactId)
            .as("given-name fragment")
            .contains(found);
        assertThat(directory.search(AGENCY, "Bogumiła Szuka")).extracting(ContactDirectory.Match::contactId)
            .as("a manager types the whole name, which is in neither column on its own")
            .contains(found);
    }

    /**
     * The third of @najem-reviewer's three scoping assertions (najem-build seq 350). The other two
     * are on {@code SearchQuery} in reporting; this one is here because Reporting has never seen a
     * name to search — see {@link ContactDirectory#search}.
     * <p>
     * Both people carry the SAME surname, so a missing predicate has something to return: with one
     * distinctive name per workspace the query would look scoped while matching on nothing.
     */
    @Test
    void searchDoesNotFindAPersonInAnotherWorkspace() {
        var mine = person(AGENCY, "Halina", "Szukanowska");
        var theirs = person(OTHER_AGENCY, "Halina", "Szukanowska");

        assertThat(directory.search(AGENCY, "Szukanowska"))
            .extracting(ContactDirectory.Match::contactId)
            .containsExactly(mine);
        assertThat(directory.search(OTHER_AGENCY, "Szukanowska"))
            .extracting(ContactDirectory.Match::contactId)
            .containsExactly(theirs);
    }

    /**
     * The reason search is served from this table rather than from a projection: erasure is a row
     * deletion, so an erased person leaves search at the instant they are erased. A copy of the name
     * in a read model would still be answering this query afterwards, which is the
     * right-to-be-forgotten defect that placing search anywhere else would build.
     */
    @Test
    void anErasedPersonIsGoneFromSearchImmediately() {
        var contactId = person(AGENCY, "Zofia", "Zapomniana");
        assertThat(directory.search(AGENCY, "Zapomniana")).isNotEmpty();

        service.erase(AGENCY, contactId, LocalDate.of(2026, 8, 4));

        assertThat(directory.search(AGENCY, "Zapomniana")).isEmpty();
    }

    @Test
    void searchRefusesToActAsADirectoryDump() {
        person(AGENCY, "Ewa", "Wildcardowa");

        assertThat(directory.search(AGENCY, "")).isEmpty();
        assertThat(directory.search(AGENCY, "  ")).isEmpty();
        assertThat(directory.search(AGENCY, null)).isEmpty();
        assertThat(directory.search(AGENCY, "%"))
            .as("a wildcard typed into a search box is a character, not everyone we hold")
            .isEmpty();
    }

    /**
     * The data-unchanged property this test was written for is unchanged; what changed is that the
     * command now REFUSES rather than quietly succeeding.
     * <p>
     * It used to pass because the scoped {@code update} matched no row — the write was safe while
     * the {@code ContactDetailsCorrected} event above it was appended to the victim's stream
     * regardless. So the assertion held for a reason narrower than it appeared: it observed the
     * projection and not the stream. Asserting the throw as well is what makes it cover both.
     */
    @Test
    void doesNotCorrectAContactBelongingToAnotherWorkspace() {
        var contactId = anna(OTHER_AGENCY);
        var eventsBefore = store.load(contactId, "Contact").events().size();

        assertThatThrownBy(() -> service.correctDetails(AGENCY, contactId,
            new ContactDetails("Wrong", "Person", "wrong@example.com", "+48000000000"),
            LocalDate.of(2026, 8, 4)))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(directory.find(OTHER_AGENCY, contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"));
        assertThat(store.load(contactId, "Contact").events())
            .as("the refused command must not have appended to the victim's stream")
            .hasSize(eventsBefore);
    }
}
