package pl.najem.contacts.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.assertj.core.groups.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.ContactsEventTypes;
import pl.najem.contacts.domain.InterestRegistered;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@Tag("integration")
class InterestServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final UUID AGENCY = UUID.randomUUID();
    static final UUID OTHER_AGENCY = UUID.randomUUID();

    static ContactService contacts;
    static InterestService interests;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        var contactDirectory = new ContactDirectory(jdbc);
        contacts = new ContactService(store, jdbc, new RetentionService(store, jdbc, contactDirectory), contactDirectory);
        interests = new InterestService(store, jdbc, contactDirectory);
    }

    private static UUID aContactIn(UUID workspaceId) {
        return contacts.register(new NewContact(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));
    }

    @Test
    void registersInterestInSeveralUnitsForOneContact() {
        var contactId = aContactIn(AGENCY);
        var unit12 = UUID.randomUUID();
        var unit14 = UUID.randomUUID();

        var first = interests.register(AGENCY, contactId, unit12, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));
        interests.register(AGENCY, contactId, unit14, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.forUnit(AGENCY, unit12))
            .extracting(Interest::interestId, Interest::contactId, Interest::unitId, Interest::status)
            .containsExactly(Tuple.tuple(first, contactId, unit12, "active"));
        assertThat(interests.forUnit(AGENCY, unit14)).hasSize(1);
    }

    @Test
    void keepsTheNegotiationAttributesOnTheInterest() {
        var contactId = aContactIn(AGENCY);
        var unitId = UUID.randomUUID();

        interests.register(AGENCY, contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        var interest = interests.forUnit(AGENCY, unitId).get(0);
        assertThat(interest.willingToPay()).isEqualByComparingTo("2400");
        assertThat(interest.desiredStart()).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void withdrawnInterestDropsOutOfTheUnitView() {
        var contactId = aContactIn(AGENCY);
        var unitId = UUID.randomUUID();
        var interestId = interests.register(AGENCY, contactId, unitId,
            new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        interests.withdraw(AGENCY, interestId, LocalDate.of(2026, 9, 1));

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void doesNotShowInterestsFromAnotherWorkspace() {
        var contactId = aContactIn(OTHER_AGENCY);
        var unitId = UUID.randomUUID();
        interests.register(OTHER_AGENCY, contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void recordsInterestOnTheContactStream() {
        var contactId = aContactIn(AGENCY);
        var unitId = UUID.randomUUID();

        var interestId = interests.register(AGENCY, contactId, unitId,
            new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.eventsFor(contactId)).contains(
            new InterestRegistered(AGENCY, interestId, contactId, unitId,
                new BigDecimal("2400"), LocalDate.of(2026, 10, 1)));
    }
}
