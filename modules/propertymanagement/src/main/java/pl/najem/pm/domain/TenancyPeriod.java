package pl.najem.pm.domain;

import java.time.LocalDate;
import java.util.UUID;

/** A slot on a unit's calendar. A null end means indefinite. */
public record TenancyPeriod(UUID tenancyId, LocalDate start, LocalDate end) {

    private LocalDate effectiveEnd() {
        return end == null ? LocalDate.MAX : end;
    }

    /**
     * Half-open [start, end): a tenancy ending 30 Jun does not clash with one starting 30 Jun.
     * Back-to-back is the common case — the outgoing tenant leaves and the incoming one arrives.
     */
    public boolean overlaps(TenancyPeriod other) {
        return start.isBefore(other.effectiveEnd()) && other.start().isBefore(effectiveEnd());
    }
}
