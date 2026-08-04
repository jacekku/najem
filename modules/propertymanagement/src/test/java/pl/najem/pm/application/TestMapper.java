package pl.najem.pm.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The mapper the tests must use, because it is the one production uses.
 *
 * <p>A bare {@code new ObjectMapper()} writes a LocalDate as {@code [2027,8,31]}; Spring Boot's
 * auto-configured mapper — the one wired into the real event store and outbox — disables
 * timestamp dates and writes {@code "2027-08-31"}. Tests built on the bare mapper were asserting
 * against a payload shape no consumer will ever receive, which is a green produced by machinery
 * that isn't production's.
 */
final class TestMapper {

    private TestMapper() {
    }

    static ObjectMapper productionLike() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
