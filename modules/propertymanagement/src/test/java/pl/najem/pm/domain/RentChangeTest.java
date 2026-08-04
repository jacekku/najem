package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RentChangeTest {

    private final UUID tenancyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private ArrayList<Object> active() {
        var history = new ArrayList<>(Tenancy.reserve(new ReserveTenancy(tenancyId, workspaceId,
            UUID.randomUUID(), List.of(UUID.randomUUID()), List.of(), LocalDate.of(2026, 1, 1),
            new Term.FixedTerm(LocalDate.of(2027, 12, 31)), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null, "NAJEM/A")));
        history.addAll(Tenancy.from(history).activate(LocalDate.of(2026, 1, 1)));
        return history;
    }

    @Test
    void scheduledChangeIsBitemporal() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE));

        var change = Tenancy.from(history).pendingRentChange(LocalDate.of(2026, 6, 1)).orElseThrow();

        assertThat(change.decidedOn()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(change.monthly().total()).isEqualByComparingTo("2600");
        assertThat(change.type()).isEqualTo(ChangeType.AGREED_CHANGE);
    }

    @Test
    void theRentInForceOnlyChangesWhenTheChangeIsApplied() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE));

        assertThat(Tenancy.from(history).monthly().total()).isEqualByComparingTo("2500");

        history.addAll(Tenancy.from(history).applyRentChange(LocalDate.of(2026, 6, 1)));

        assertThat(Tenancy.from(history).monthly().total()).isEqualByComparingTo("2600");
        assertThat(Tenancy.from(history).pendingRentChange(LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void unlawfulUnilateralIncreaseWarnsButIsAccepted() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.UNILATERAL_INCREASE));

        assertThat(Tenancy.from(history).warnings().messages())
            .anyMatch(m -> m.contains("3 months"));
    }

    @Test
    void aUnilateralIncreaseWithThreeMonthsNoticeDoesNotWarn() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.UNILATERAL_INCREASE));

        assertThat(Tenancy.from(history).warnings().messages())
            .noneMatch(m -> m.contains("3 months"));
    }

    @Test
    void anAgreedChangeAtShortNoticeIsFine() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE));

        assertThat(Tenancy.from(history).warnings().messages())
            .noneMatch(m -> m.contains("3 months"));
    }

    @Test
    void cancelledChangeIsNoLongerPending() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2600"), null), ChangeType.AGREED_CHANGE));
        history.addAll(Tenancy.from(history).cancelRentChange(LocalDate.of(2026, 6, 1)));

        assertThat(Tenancy.from(history).pendingRentChange(LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void applyingAChangeThatIsNoLongerPendingIsRejected() {
        var history = active();

        assertThatThrownBy(() -> Tenancy.from(history).applyRentChange(LocalDate.of(2026, 6, 1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aChangeCanCarryANewComponentBreakdown() {
        var history = active();
        history.addAll(Tenancy.from(history).scheduleRentChange(
            LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 1),
            new MonthlyAmount(new BigDecimal("2800"),
                new MonthlyAmount.Breakdown(new BigDecimal("2400"), new BigDecimal("200"),
                    new BigDecimal("200"))),
            ChangeType.INDEXATION));
        history.addAll(Tenancy.from(history).applyRentChange(LocalDate.of(2026, 6, 1)));

        var monthly = Tenancy.from(history).monthly();
        assertThat(monthly.componentSplitInContract()).isTrue();
        assertThat(monthly.breakdown().rent()).isEqualByComparingTo("2400");
    }
}
