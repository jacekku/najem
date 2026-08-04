package pl.najem.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EventFeedTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static EventFeed feed;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/reporting").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        feed = new EventFeed(jdbc, new ObjectMapper());
    }

    private static long append(String streamType, String eventType, String payload) {
        return jdbc.queryForObject("""
            insert into events(stream_id, stream_type, version, event_type, payload)
            values (?,?,?,?,cast(? as jsonb)) returning global_seq
            """, Long.class, UUID.randomUUID(), streamType, 0L, eventType, payload);
    }

    @Test
    void readsEventsInGlobalSequenceOrderAndResumesFromACursor() {
        long first = append("Tenancy", "TenancyReserved", "{\"tenancyId\":\"t1\"}");
        long second = append("Tenancy", "TenancyActivated", "{\"tenancyId\":\"t1\"}");
        long third = append("Unit", "UnitOpenedToRent", "{\"unitId\":\"u1\"}");

        assertThat(feed.since(first - 1, 10)).extracting(FeedEntry::globalSeq)
            .containsExactly(first, second, third);
        assertThat(feed.since(second, 10)).extracting(FeedEntry::globalSeq).containsExactly(third);
        assertThat(feed.since(third, 10)).isEmpty();
    }

    @Test
    void exposesTheEventAsATypeNameAndATreeRatherThanAnothersModulesClass() {
        long seq = append("Tenancy", "TenancyActivated", "{\"unitId\":\"u9\",\"monthlyTotal\":3000}");

        var entry = feed.since(seq - 1, 10).stream().filter(e -> e.globalSeq() == seq).findFirst().orElseThrow();

        assertThat(entry.streamType()).isEqualTo("Tenancy");
        assertThat(entry.eventType()).isEqualTo("TenancyActivated");
        assertThat(entry.payload().get("unitId").asText()).isEqualTo("u9");
        assertThat(entry.payload().get("monthlyTotal").asInt()).isEqualTo(3000);
    }

    /** The feed is not a registry: an event type it has never heard of is data, not an error. */
    @Test
    void passesThroughAnEventTypeItHasNeverSeen() {
        long seq = append("Tenancy", "SomethingInventedNextWeek", "{\"whatever\":true}");

        assertThat(feed.since(seq - 1, 10)).extracting(FeedEntry::eventType)
            .contains("SomethingInventedNextWeek");
    }

    /**
     * The allowlist ruling (najem-build seq 65 pt 2) enforced structurally rather than by
     * convention: streams are default-private, and a projection cannot read one by forgetting
     * the rule, because the feed never hands it over.
     */
    @Test
    void doesNotHandOverAStreamThatIsNotOnTheAllowlist() {
        long allowed = append("Tenancy", "TenancyActivated", "{}");
        long forbidden = append("Invitation", "MemberInvited", "{\"email\":\"never@example.com\"}");

        var entries = feed.since(allowed - 1, 10);

        assertThat(entries).extracting(FeedEntry::globalSeq).contains(allowed).doesNotContain(forbidden);
        assertThat(entries).extracting(FeedEntry::streamType).doesNotContain("Invitation");
    }

    /** A forbidden stream must not consume the cursor's window either, or reads would silently stall. */
    @Test
    void aForbiddenStreamDoesNotStallTheCursor() {
        long before = append("Unit", "UnitClosedToRent", "{}");
        append("Invitation", "MemberInvited", "{}");
        append("Invitation", "MemberInvited", "{}");
        long after = append("Unit", "UnitOpenedToRent", "{}");

        assertThat(feed.since(before, 2)).extracting(FeedEntry::globalSeq).containsExactly(after);
    }

    @Test
    void honoursTheBatchLimit() {
        long first = append("Unit", "UnitDetailsUpdated", "{}");
        append("Unit", "UnitDetailsUpdated", "{}");
        append("Unit", "UnitDetailsUpdated", "{}");

        assertThat(feed.since(first - 1, 2)).hasSize(2);
    }

    /** Every stream the ruling names, so a typo in the allowlist fails here rather than at read time. */
    @Test
    void allowsExactlyTheStreamsTheRulingNames() {
        assertThat(EventFeed.ALLOWED_STREAMS)
            .containsExactlyInAnyOrder("Property", "Unit", "Tenancy", "TenancyLedger", "Payment",
                "Contact", "Workspace", "User");
    }
}
