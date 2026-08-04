package pl.najem.pm.adapter.rest;

import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.OverlappingTenancyException;

import static org.assertj.core.api.Assertions.assertThat;

class PmExceptionHandlerTest {

    private final PmExceptionHandler handler = new PmExceptionHandler();

    /**
     * The one hard invariant deserves a status a caller can act on. A 500 says "we broke"; a 409
     * says "your request conflicts with reality", which is exactly what an overlapping tenancy is.
     */
    @Test
    void overlappingReservationIsAConflictNotAServerError() {
        var response = handler.handle(new OverlappingTenancyException("overlaps tenancy X"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("error", "overlaps tenancy X");
    }

    /** A missing legal form or an unknown correction key is the caller's mistake, not ours. */
    @Test
    void abadArgumentIsFourHundred() {
        var response = handler.handle(new IllegalArgumentException("legalForm is required"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "legalForm is required");
    }

    /** Ending an already-ended tenancy is a conflict with current state, not a bad request. */
    @Test
    void awrongLifecycleTransitionIsFourHundredAndNine() {
        var response = handler.handle(new IllegalStateException("Only a live tenancy can be ended"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("error", "Only a live tenancy can be ended");
    }

    /**
     * A null message must not become the string "null" in a manager's face. This only shows up in
     * production, because every test throws with a message.
     */
    @Test
    void amessagelessFailureStillProducesAReadableBody() {
        var response = handler.handle(new IllegalStateException());

        assertThat(response.getBody().get("error")).isNotNull().isNotEqualTo("null");
    }
}
