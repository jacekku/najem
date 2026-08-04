package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnitCalendarTest {

    private final UUID unitId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private List<Object> unitWithPeriod(LocalDate start, LocalDate end, UUID tenancyId) {
        var history = new ArrayList<>(
            Unit.add(unitId, workspaceId, UUID.randomUUID(), "M1", new BigDecimal("2500")));
        history.addAll(Unit.from(history).registerTenancyPeriod(tenancyId, start, end));
        return history;
    }

    @Test
    void backToBackPeriodsAreAllowed() {
        var history = unitWithPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), UUID.randomUUID());

        var events = Unit.from(history).registerTenancyPeriod(
            UUID.randomUUID(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31));

        assertThat(events).hasSize(1);
    }

    @Test
    void overlappingPeriodIsRejected() {
        var existing = UUID.randomUUID();
        var history = unitWithPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), existing);

        assertThatThrownBy(() -> Unit.from(history).registerTenancyPeriod(
                UUID.randomUUID(), LocalDate.of(2026, 6, 29), LocalDate.of(2026, 12, 31)))
            .isInstanceOf(OverlappingTenancyException.class)
            .hasMessageContaining(existing.toString());
    }

    @Test
    void aPeriodFullyInsideAnotherIsRejected() {
        var history = unitWithPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), UUID.randomUUID());

        assertThatThrownBy(() -> Unit.from(history).registerTenancyPeriod(
                UUID.randomUUID(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1)))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    @Test
    void aPeriodFullyEnclosingAnotherIsRejected() {
        var history = unitWithPeriod(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1), UUID.randomUUID());

        assertThatThrownBy(() -> Unit.from(history).registerTenancyPeriod(
                UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    @Test
    void indefiniteTenancyBlocksEverythingAfterItsStart() {
        var history = unitWithPeriod(LocalDate.of(2026, 1, 1), null, UUID.randomUUID());

        assertThatThrownBy(() -> Unit.from(history).registerTenancyPeriod(
                UUID.randomUUID(), LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31)))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    @Test
    void aPeriodEndingBeforeAnIndefiniteTenancyStartsIsAllowed() {
        var history = unitWithPeriod(LocalDate.of(2026, 6, 1), null, UUID.randomUUID());

        var events = Unit.from(history).registerTenancyPeriod(
            UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1));

        assertThat(events).hasSize(1);
    }

    @Test
    void releasedPeriodFreesTheSlot() {
        var cancelled = UUID.randomUUID();
        var history = new ArrayList<>(
            unitWithPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), cancelled));
        history.addAll(Unit.from(history).releaseTenancyPeriod(cancelled));

        var events = Unit.from(history).registerTenancyPeriod(
            UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));

        assertThat(events).hasSize(1);
        assertThat(Unit.from(history).periods()).isEmpty();
    }

    @Test
    void reservationOnAClosedUnitIsAllowed() {
        var history = new ArrayList<>(
            Unit.add(unitId, workspaceId, UUID.randomUUID(), "M1", new BigDecimal("2500")));
        history.addAll(Unit.from(history).closeToRent("under construction"));

        var events = Unit.from(history).registerTenancyPeriod(
            UUID.randomUUID(), LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31));

        assertThat(events).hasSize(1);
    }
}
