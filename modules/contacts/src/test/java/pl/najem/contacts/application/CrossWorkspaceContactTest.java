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
     * Erasing somebody else's contact is a refusal, not a quiet success.
     * <p>
     * This test asserted the opposite until @najem-reviewer contested it at najem-build seq 266.
     * My reasoning was that deleting nothing is the correct outcome of an erase command so there is
     * nothing to report, and that a 404-versus-204 difference would make this an existence oracle.
     * The second half was already answered by the other four commands, which merge unknown and
     * foreign into one status — so this endpoint discloses nothing they do not. The first half is
     * the actual error: <b>a 204 tells the caller the person's data is gone.</b> A mistyped id, or
     * a client pointed at the wrong workspace, produced that answer while the data remained.
     */
    @Test
    void refusesToEraseSomebodyElsesContactRatherThanReportingSuccess() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        var before = eventCount(contactId);

        assertThatThrownBy(() -> contacts.erase(INTRUDER, contactId, LocalDate.of(2027, 9, 1)))
            .isInstanceOf(NoSuchContactException.class);

        assertThat(directory.find(OWNER, contactId)).isPresent();
        assertThat(eventCount(contactId)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_erasure_log where contact_id = ?", Integer.class, contactId))
            .as("a refused erasure must not leave a tombstone claiming it happened")
            .isZero();
    }

    /**
     * The wrinkle @najem-reviewer named before I hit it: erasure deletes the {@code contacts_person}
     * row, so a gate that only asks "is this contact yours" would 404 on the second erasure of a
     * contact you had just legitimately erased. The erasure log is what keeps it yours.
     */
    @Test
    void erasingYourOwnContactTwiceStaysIdempotentRatherThanBecomingA404() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        contacts.erase(OWNER, contactId, LocalDate.of(2027, 9, 1));
        var eventsAfterFirst = eventCount(contactId);

        contacts.erase(OWNER, contactId, LocalDate.of(2027, 9, 2));

        assertThat(directory.find(OWNER, contactId)).isEmpty();
        assertThat(eventCount(contactId))
            .as("the repeat must not append a second ContactErased")
            .isEqualTo(eventsAfterFirst);
        assertThat(jdbc.queryForObject(
            "select erased_on from contacts_erasure_log where contact_id = ?", LocalDate.class, contactId))
            .as("and must not rewrite the date the erasure actually happened")
            .isEqualTo(LocalDate.of(2027, 9, 1));
    }

    /**
     * Withdrawing an interest that is not yours refuses with the module's own not-found type, not
     * with a leaked Spring data-access exception.
     * <p>
     * The lookup was always workspace-scoped, so this was never a boundary hole — it was a
     * <b>500 where a 404 belongs</b>. That matters for two reasons beyond tidiness: a caller cannot
     * distinguish a bad id from the server being broken, and anything alerting on 5xx fires on
     * ordinary traffic until people stop reading the alert.
     */
    @Test
    void refusesToWithdrawAnInterestBelongingToAnotherWorkspace() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        var unitId = UUID.randomUUID();
        var interestId = interests.register(OWNER, contactId, unitId,
            new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> interests.withdraw(INTRUDER, interestId, LocalDate.of(2026, 11, 1)))
            .isInstanceOf(NoSuchInterestException.class)
            .isNotInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(interests.forUnit(OWNER, unitId))
            .as("the owner's interest is untouched by the refused withdrawal")
            .hasSize(1);
    }

    /** An id that names nothing at all answers identically, so the status is not an oracle. */
    @Test
    void refusesToWithdrawAnInterestThatDoesNotExist() {
        assertThatThrownBy(() -> interests.withdraw(OWNER, UUID.randomUUID(), LocalDate.of(2026, 11, 1)))
            .isInstanceOf(NoSuchInterestException.class);
    }

    /** And a stranger may not use the log as an oracle either: already-erased-elsewhere is the same 404. */
    @Test
    void refusesToEraseAContactAnotherWorkspaceHasAlreadyErased() {
        var contactId = aContactOfTheOwner(LocalDate.of(2027, 8, 3));
        contacts.erase(OWNER, contactId, LocalDate.of(2027, 9, 1));

        assertThatThrownBy(() -> contacts.erase(INTRUDER, contactId, LocalDate.of(2027, 9, 2)))
            .isInstanceOf(NoSuchContactException.class);
    }
}
