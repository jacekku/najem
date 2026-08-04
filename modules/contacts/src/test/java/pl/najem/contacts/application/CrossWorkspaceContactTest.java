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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every contacts entry point takes {@code contactId} from the caller. This asserts that none of
 * them will act on a contact belonging to somebody else.
 * <p>
 * The SQL was never the exposure here — every {@code update}/{@code delete} in this module already
 * carries {@code workspace_id}, so the shape @najem-reviewer described at najem-build seq 227
 * (<em>a projection write keyed on a caller-supplied id</em>) finds nothing in contacts. The
 * exposure is one layer up: the services append to the contact's <b>event stream</b> before, or
 * without, establishing that the contact is the caller's. A scoped write that never runs is safe;
 * an unscoped append that always runs is not.
 * <p>
 * The worst instance is silent in the direction that matters for personal data — see
 * {@link #doesNotLetAForeignHoldSuppressTheOwnersRetentionReport()}. Grepping for the reviewer's
 * shape, finding it absent, and concluding contacts was unaffected is the mistake this class exists
 * to have not made.
 */
@Testcontainers
class CrossWorkspaceContactTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final UUID OWNER = UUID.randomUUID();
    static final UUID INTRUDER = UUID.randomUUID();

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContactService contacts;
    static InterestService interests;
    static ContactDirectory directory;
    static RetentionService retention;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        directory = new ContactDirectory(jdbc);
        retention = new RetentionService(store, jdbc, directory);
        contacts = new ContactService(store, jdbc, retention, directory);
        interests = new InterestService(store, jdbc, directory);
    }

    private static UUID aContactOfTheOwner(LocalDate retainUntil) {
        return contacts.register(new NewContact(OWNER,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), retainUntil));
    }

    private static int eventCount(UUID contactId) {
        return store.load(contactId, "Contact").events().size();
    }

    /**
     * The one that fails toward keeping personal data, and reports nothing while it does.
     * <p>
     * {@code hasActiveHold} filters holds by workspace; {@code dueForErasure}'s subquery did not.
     * So a hold raised by anybody at all removed the contact from the owner's erasure-due report,
     * while leaving the erasure gate itself open — the two checks of one rule disagreed, and they
     * disagreed in the worst direction: the operator is told there is nothing to erase.
     */
    @Test
    void doesNotLetAForeignHoldSuppressTheOwnersRetentionReport() {
        var contactId = aContactOfTheOwner(LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() ->
            retention.setHold(INTRUDER, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3)))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(retention.dueForErasure(OWNER, LocalDate.of(2026, 8, 3)))
            .as("a contact past its retention date must stay on the owner's report")
            .contains(contactId);
    }

    @Test
    void refusesToRaiseAHoldOnSomebodyElsesContact() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        var before = eventCount(contactId);

        assertThatThrownBy(() ->
            retention.setHold(INTRUDER, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3)))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(retention.activeHolds(OWNER, contactId)).isEmpty();
        assertThat(eventCount(contactId))
            .as("a refused command must leave no event behind")
            .isEqualTo(before);
    }

    /**
     * Releasing is the more dangerous half: a hold exists to stop an erasure, so a stranger who can
     * release one can bring forward the deletion of a person the owner is legally required to keep.
     */
    @Test
    void refusesToReleaseAHoldOnSomebodyElsesContact() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        retention.setHold(OWNER, contactId, "tax-5y", LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() ->
            retention.releaseHold(INTRUDER, contactId, "tax-5y", LocalDate.of(2026, 9, 1)))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(retention.activeHolds(OWNER, contactId)).containsExactly("tax-5y");
    }

    /**
     * An interest row carries {@code workspace_id}, so a foreign one is invisible to the owner —
     * including to {@code erase()}, which deletes only the owner's. The row therefore SURVIVES the
     * erasure, still naming the erased contact, and right-to-be-forgotten is incomplete in a way
     * nothing reports.
     */
    @Test
    void refusesToRegisterAnInterestAgainstSomebodyElsesContact() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));

        assertThatThrownBy(() -> interests.register(INTRUDER, contactId, UUID.randomUUID(),
            new BigDecimal("2400"), LocalDate.of(2026, 10, 1)))
            .isInstanceOf(NoSuchContactException.class);

        contacts.erase(OWNER, contactId, LocalDate.of(2027, 9, 1));

        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_interest where contact_id = ?", Integer.class, contactId))
            .as("no row naming an erased contact may outlive the erasure, in any workspace")
            .isZero();
    }

    @Test
    void refusesToCorrectSomebodyElsesContactDetails() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        var before = eventCount(contactId);

        assertThatThrownBy(() -> contacts.correctDetails(INTRUDER, contactId,
            new ContactDetails("Wrong", "Person", "x@example.com", "+48000000000"),
            LocalDate.of(2026, 9, 1)))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(directory.find(OWNER, contactId))
            .get()
            .extracting(ContactDetails::givenName)
            .isEqualTo("Anna");
        assertThat(eventCount(contactId)).isEqualTo(before);
    }

    /**
     * Erasure already returned quietly for a foreign contact, and that stays the behaviour rather
     * than becoming a throw. Deleting nothing IS the correct outcome of an erase command, so there
     * is no failure to report — and a caller learning "that id exists elsewhere" from a 404-versus-
     * 204 difference would make this endpoint an existence oracle over other agencies' contacts.
     */
    @Test
    void erasingSomebodyElsesContactStaysASilentNoOp() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        var before = eventCount(contactId);

        contacts.erase(INTRUDER, contactId, LocalDate.of(2027, 9, 1));

        assertThat(directory.find(OWNER, contactId)).isPresent();
        assertThat(eventCount(contactId)).isEqualTo(before);
    }
}
