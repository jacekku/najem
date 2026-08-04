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
import pl.najem.contacts.domain.ContactErased;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RetentionServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final UUID AGENCY = UUID.randomUUID();
    static final UUID OTHER_AGENCY = UUID.randomUUID();

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

    private static UUID aContactRetainedUntil(LocalDate retainUntil) {
        return contacts.register(new NewContact(AGENCY,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), retainUntil));
    }

    @Test
    void erasureDeletesPersonalDataButLeavesTheEventStreamIntact() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        var unitId = UUID.randomUUID();
        interests.register(AGENCY, contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));
        var eventCountBefore = store.load(contactId, "Contact").events().size();

        contacts.erase(AGENCY, contactId, LocalDate.of(2027, 9, 1));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where contact_id = ?", Integer.class, contactId)).isZero();
        assertThat(store.load(contactId, "Contact").events()).hasSize(eventCountBefore + 1);
        assertThat(store.load(contactId, "Contact").events())
            .contains(new ContactErased(AGENCY, contactId, LocalDate.of(2027, 9, 1)));
        assertThat(jdbc.queryForObject(
            "select erased_on from contacts_erasure_log where contact_id = ?", LocalDate.class, contactId))
            .isEqualTo(LocalDate.of(2027, 9, 1));
        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void erasureIsRefusedWhileARetentionHoldIsActive() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, LocalDate.of(2027, 9, 1)))
            .isInstanceOf(RetentionHoldActiveException.class)
            .hasMessageContaining("ledger-referenced");

        assertThat(directory.find(AGENCY, contactId)).isPresent();
    }

    @Test
    void erasureIsAllowedOnceEveryHoldIsReleased() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));
        retention.releaseHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2032, 1, 1));

        contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    @Test
    void erasureIsRefusedWhileAnyOneOfSeveralHoldsRemains() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));
        retention.setHold(AGENCY, contactId, "tax-5y", LocalDate.of(2026, 8, 3));
        retention.releaseHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2032, 1, 1));

        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2)))
            .isInstanceOf(RetentionHoldActiveException.class)
            .hasMessageContaining("tax-5y");
    }

    /**
     * A guarantor on two flats is the ordinary case, not the exotic one. Releasing one
     * tenancy's hold must not free a contact the other tenancy's ledger still justifies —
     * and the failure would be silent, so it is asserted rather than reasoned about.
     */
    @Test
    void releasingOneSourcesHoldLeavesAnotherSourcesHoldStanding() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        var tenancyA = UUID.randomUUID();
        var tenancyB = UUID.randomUUID();
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2026, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancyB.toString(), LocalDate.of(2026, 8, 3));

        retention.releaseHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2032, 1, 1));

        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2)))
            .isInstanceOf(RetentionHoldActiveException.class)
            .hasMessageContaining("ledger-referenced");
        assertThat(directory.find(AGENCY, contactId)).isPresent();
    }

    @Test
    void erasureIsAllowedOnceEverySourcesHoldIsReleased() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        var tenancyA = UUID.randomUUID();
        var tenancyB = UUID.randomUUID();
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2026, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancyB.toString(), LocalDate.of(2026, 8, 3));

        retention.releaseHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2032, 1, 1));
        retention.releaseHold(AGENCY, contactId, "ledger-referenced", tenancyB.toString(), LocalDate.of(2032, 1, 1));

        contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    /** The same source re-asserting a hold it already holds is normal traffic under a re-fan-out, not an error. */
    @Test
    void reAssertingTheSameSourcesHoldIsIdempotent() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        var tenancyA = UUID.randomUUID();
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2026, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2026, 9, 3));

        retention.releaseHold(AGENCY, contactId, "ledger-referenced", tenancyA.toString(), LocalDate.of(2032, 1, 1));

        contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    /** The manual API carries no source; it must keep working exactly as before. */
    @Test
    void aManuallySetHoldStillBlocksErasureAndReleasesOnItsOwn() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "manager-judgement", LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, LocalDate.of(2027, 9, 1)))
            .isInstanceOf(RetentionHoldActiveException.class);

        retention.releaseHold(AGENCY, contactId, "manager-judgement", LocalDate.of(2032, 1, 1));
        contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    /** One reason held by two sources is one reason to a caller — the message names causes, not rows. */
    @Test
    void reportsEachBlockingReasonOnceHoweverManySourcesRaisedIt() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", UUID.randomUUID().toString(), LocalDate.of(2026, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", UUID.randomUUID().toString(), LocalDate.of(2026, 8, 3));

        assertThat(retention.activeHolds(AGENCY, contactId)).containsExactly("ledger-referenced");
    }

    @Test
    void reportsContactsPastTheirRetentionDateWithoutDeletingThem() {
        var stale = aContactRetainedUntil(LocalDate.of(2026, 1, 1));
        var fresh = aContactRetainedUntil(LocalDate.of(2099, 1, 1));
        var neverDue = aContactRetainedUntil(null);
        var held = aContactRetainedUntil(LocalDate.of(2026, 1, 1));
        retention.setHold(AGENCY, held, "ledger-referenced", LocalDate.of(2026, 1, 1));

        var due = retention.dueForErasure(AGENCY, LocalDate.of(2026, 8, 3));

        assertThat(due).contains(stale).doesNotContain(fresh, neverDue, held);
        assertThat(directory.find(AGENCY, stale)).isPresent();
    }

    @Test
    void doesNotReportContactsFromAnotherWorkspace() {
        var stale = aContactRetainedUntil(LocalDate.of(2026, 1, 1));

        assertThat(retention.dueForErasure(OTHER_AGENCY, LocalDate.of(2026, 8, 3))).doesNotContain(stale);
    }

    @Test
    void doesNotEraseAContactBelongingToAnotherWorkspace() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));

        contacts.erase(OTHER_AGENCY, contactId, LocalDate.of(2027, 9, 1));

        assertThat(directory.find(AGENCY, contactId)).isPresent();
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_erasure_log where contact_id = ?", Integer.class, contactId))
            .as("an erasure that erased nothing must not leave a tombstone claiming it did")
            .isZero();
        assertThat(store.load(contactId, "Contact").events()).noneMatch(ContactErased.class::isInstance);
    }
}
