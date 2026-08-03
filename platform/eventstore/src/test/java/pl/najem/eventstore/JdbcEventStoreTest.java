package pl.najem.eventstore;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
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

        var stream = store.load(id);

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
        var stream = store.load(UUID.randomUUID());

        assertThat(stream.version()).isZero();
        assertThat(stream.events()).isEmpty();
    }
}
