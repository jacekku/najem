package pl.najem.pm.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * Statutory inspection cadence (art. 62 Prawo budowlane). The interval belongs to the type
 * because it is set by statute, not by the manager — which is also why a missing type is
 * rejected rather than defaulted: the type IS the deadline.
 */
public enum InspectionType {

    GAS(Period.ofYears(1)),
    CHIMNEY(Period.ofYears(1)),
    ELECTRICAL_5YR(Period.ofYears(5)),
    ANNUAL(Period.ofYears(1)),
    SMOKE_CO(Period.ofYears(1));

    private final Period interval;

    InspectionType(Period interval) {
        this.interval = interval;
    }

    public LocalDate nextDue(LocalDate performedOn) {
        return performedOn.plus(interval);
    }

    public String wireName() {
        return name().toLowerCase().replace('_', '-');
    }
}
