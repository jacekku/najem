package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionScheduleTest {

    private final UUID propertyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void nextDueFollowsTheStatutoryInterval() {
        assertThat(InspectionType.GAS.nextDue(LocalDate.of(2026, 5, 10)))
            .isEqualTo(LocalDate.of(2027, 5, 10));
        assertThat(InspectionType.ELECTRICAL_5YR.nextDue(LocalDate.of(2026, 5, 10)))
            .isEqualTo(LocalDate.of(2031, 5, 10));
    }

    @Test
    void recordingAnInspectionEmitsItWithItsNextDueDate() {
        var property = property();

        var events = property.recordInspection(InspectionType.CHIMNEY, LocalDate.of(2026, 5, 10),
            "s3://docs/chimney.pdf", "no findings");

        assertThat(events).containsExactly(new PropertyEvents.InspectionCompleted(workspaceId,
            propertyId, InspectionType.CHIMNEY, LocalDate.of(2026, 5, 10),
            LocalDate.of(2027, 5, 10), "s3://docs/chimney.pdf", "no findings"));
    }

    /** A re-inspection moves the deadline; the property tracks the latest per type. */
    @Test
    void thelatestInspectionOfATypeIsTheOneThatSetsTheDeadline() {
        var history = new java.util.ArrayList<>(Property.create(propertyId, workspaceId,
            "Testowa 1", List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))));
        history.addAll(Property.from(history).recordInspection(InspectionType.GAS,
            LocalDate.of(2026, 5, 10), null, "ok"));
        history.addAll(Property.from(history).recordInspection(InspectionType.GAS,
            LocalDate.of(2027, 4, 1), null, "ok"));

        assertThat(Property.from(history).nextDue(InspectionType.GAS))
            .contains(LocalDate.of(2028, 4, 1));
    }

    /** A type never inspected has no deadline — absence is not "overdue since forever". */
    @Test
    void atypeNeverInspectedHasNoDeadline() {
        assertThat(property().nextDue(InspectionType.SMOKE_CO)).isEmpty();
    }

    /** Rule 7: the type decides the interval, so it is never defaulted. */
    @Test
    void amissingInspectionTypeIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> property().recordInspection(null, LocalDate.of(2026, 5, 10), null, "ok"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Property property() {
        return Property.from(Property.create(propertyId, workspaceId, "Testowa 1",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))));
    }
}
