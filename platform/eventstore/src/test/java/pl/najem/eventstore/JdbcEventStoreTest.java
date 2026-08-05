package pl.najem.eventstore;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class JdbcEventStoreTest {

    record SampleEvent(String v) {}

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcEventStore store;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/eventstore").load().migrate();
        var registry = new EventTypeRegistry();
        registry.register(SampleEvent.class);
        store = new JdbcEventStore(new JdbcTemplate(dataSource),
            new ObjectMapper().registerModule(new JavaTimeModule()), registry);
    }

    @Test
    void appendsAndLoadsInOrder() {
        var id = UUID.randomUUID();
        store.append(id, "Test", 0, List.of(new SampleEvent("a"), new SampleEvent("b")), List.of());

        var stream = store.load(id, "Test");

        assertThat(stream.version()).isEqualTo(2);
        assertThat(stream.events()).containsExactly(new SampleEvent("a"), new SampleEvent("b"));
    }

    @Test
    void rejectsStaleExpectedVersion() {
        var id = UUID.randomUUID();
        store.append(id, "Test", 0, List.of(new SampleEvent("a")), List.of());

        assertThatThrownBy(() -> store.append(id, "Test", 0, List.of(new SampleEvent("b")), List.of()))
            .isInstanceOf(ConcurrencyException.class);
    }

    @Test
    void loadOfUnknownStreamIsEmptyAtVersionZero() {
        var stream = store.load(UUID.randomUUID(), "Test");

        assertThat(stream.version()).isZero();
        assertThat(stream.events()).isEmpty();
    }

    // Two bounded contexts may name a stream after the same subject -- PM writes (tenancyId,
    // "Tenancy") while accounting writes (tenancyId, "TenancyLedger") for the same tenancy.
    // Those are two streams that happen to share an id, and neither module may see the other's
    // events: rehydrating an aggregate from a foreign event is an immediate crash, and it is
    // not a case a module can defend against, since it cannot know the other module exists.
    @Test
    void oneIdUnderTwoTypesIsTwoStreams() {
        var id = UUID.randomUUID();
        store.append(id, "Tenancy", 0, List.of(new SampleEvent("pm")), List.of());
        store.append(id, "TenancyLedger", 0, List.of(new SampleEvent("acc")), List.of());

        assertThat(store.load(id, "Tenancy").events()).containsExactly(new SampleEvent("pm"));
        assertThat(store.load(id, "TenancyLedger").events()).containsExactly(new SampleEvent("acc"));
    }

    // The read above is only half of it. Each type versions from its OWN history, so both
    // streams legitimately hold a version 1 under one id -- which the uniqueness constraint
    // has to permit. If it does not, the second module's first append dies with a
    // ConcurrencyException naming a concurrent writer that does not exist, and retrying
    // fails identically forever.
    @Test
    void eachTypeVersionsIndependentlyUnderASharedId() {
        var id = UUID.randomUUID();
        store.append(id, "Tenancy", 0, List.of(new SampleEvent("pm-1")), List.of());
        store.append(id, "TenancyLedger", 0, List.of(new SampleEvent("acc-1")), List.of());
        store.append(id, "Tenancy", 1, List.of(new SampleEvent("pm-2")), List.of());

        assertThat(store.load(id, "Tenancy").version()).isEqualTo(2);
        assertThat(store.load(id, "TenancyLedger").version()).isEqualTo(1);
    }

    // Optimistic concurrency must still be per (id, type) rather than per id.
    @Test
    void rejectsStaleExpectedVersionWithinOneType() {
        var id = UUID.randomUUID();
        store.append(id, "Tenancy", 0, List.of(new SampleEvent("a")), List.of());
        store.append(id, "TenancyLedger", 0, List.of(new SampleEvent("b")), List.of());

        assertThatThrownBy(() -> store.append(id, "Tenancy", 0, List.of(new SampleEvent("c")), List.of()))
            .isInstanceOf(ConcurrencyException.class);
    }
}
