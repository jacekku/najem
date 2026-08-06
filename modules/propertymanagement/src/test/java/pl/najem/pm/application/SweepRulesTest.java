package pl.najem.pm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.DocType;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three process managers with no database. Until this class existed every test that drove a
 * sweep booted a container, so the loop that decides whether anything happens at all cost two
 * minutes to ask about — and the rule it exists to hold (one bad subject costs exactly that
 * subject) is the kind nobody runs a two-minute suite to check.
 *
 * <p>{@code TenancyStartProcessTest}, {@code RentChangeProcessTest} and
 * {@code EndOfTenancyProcessTest} run the same rules against Postgres and win when they disagree.
 */
class SweepRulesTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private InMemoryEventStore store;
    private InMemoryTenancies tenancyRows;
    private InMemoryProcessDue due;
    private PortfolioService portfolio;
    private TenancyService tenancies;
    private ChecklistService checklists;

    private final UUID agency = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        var portfolioRows = new InMemoryPortfolioProjection();
        tenancyRows = new InMemoryTenancies(portfolioRows);
        due = new InMemoryProcessDue();
        portfolio = new PortfolioService(store, portfolioRows);
        tenancies = new TenancyService(store, tenancyRows, due);
        checklists = new ChecklistService(store);
    }

    private TenancyStartProcess starts() {
        return new TenancyStartProcess(due, tenancies, clockOn(LocalDate.of(2026, 9, 1)));
    }

    private static Clock clockOn(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(WARSAW).toInstant(), WARSAW);
    }

    private UUID unit() {
        var propertyId = portfolio.createProperty(agency, "Testowa 1, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        return portfolio.addUnit(agency, propertyId, "M" + UUID.randomUUID(), new BigDecimal("2500"));
    }

    private UUID reserveStarting(LocalDate startDate) {
        return reserveStarting(startDate, LegalForm.ZWYKLY);
    }

    private UUID reserveStarting(LocalDate startDate, LegalForm legalForm) {
        return tenancies.reserve(agency, new ReserveTenancy(null, null, unit(),
            List.of(UUID.randomUUID()), List.of(), startDate,
            new Term.FixedTerm(startDate.plusYears(1)), legalForm,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
    }

    private UUID activeTenancyEnding(LocalDate endDate) {
        var tenancyId = tenancies.reserve(agency, new ReserveTenancy(null, null, unit(),
            List.of(UUID.randomUUID()), List.of(), endDate.minusYears(1),
            new Term.FixedTerm(endDate), LegalForm.ZWYKLY,
            new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
        tenancies.activate(agency, tenancyId, endDate.minusYears(1));
        return tenancyId;
    }

    // ---- TenancyStartProcess -------------------------------------------------------------

    @Test
    void areservationWhoseStartDateHasArrivedActivates() {
        var tenancyId = reserveStarting(LocalDate.of(2026, 9, 1));

        assertThat(starts().runDue(LocalDate.of(2026, 9, 1)).done()).isEqualTo(1);

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.ACTIVE);
    }

    /**
     * A repeated sweep never activates one twice, and does not fail trying. Note what actually
     * holds this: the aggregate refuses, because the second pass finds the tenancy no longer
     * RESERVED and disarms. The fired mark is a second line — see below.
     */
    @Test
    void asecondSweepDoesNotActivateTheSameTenancyAgain() {
        reserveStarting(LocalDate.of(2026, 9, 1));
        var process = starts();
        process.runDue(LocalDate.of(2026, 9, 1));

        var second = process.runDue(LocalDate.of(2026, 9, 2));

        assertThat(second.done()).isZero();
        assertThat(second.clean()).isTrue();
    }

    /**
     * The fired mark itself, which nothing tested until this assertion existed.
     *
     * <p>Deleting all three {@code due.markFired} calls left the whole PM suite green — fast tier
     * and Testcontainers both — because every path that could re-fire is already refused by the
     * aggregate and disarms on the way out. That makes the mark look redundant, and it is not: it
     * is what stops a fired timer being handed back by {@code due()} at all, so the redundant work
     * is never even attempted. The test above passes with it deleted; this one does not.
     *
     * <p>Asserted against {@code due()} rather than against a row, because "does this subject come
     * back on the next sweep" is the behaviour, and {@code fired_at is null} is only how the
     * statement achieves it (rule 14).
     */
    @Test
    void afiredTimerIsNotHandedBackByThenextSweep() {
        var tenancyId = reserveStarting(LocalDate.of(2026, 9, 1));

        starts().runDue(LocalDate.of(2026, 9, 1));

        assertThat(due.due(TenancyStartProcess.KIND, LocalDate.of(2026, 9, 2)))
            .doesNotContain(tenancyId);
    }

    @Test
    void areservationWhoseStartDateHasNotArrivedIsLeftAlone() {
        var tenancyId = reserveStarting(LocalDate.of(2026, 9, 1));

        assertThat(starts().runDue(LocalDate.of(2026, 8, 31)).done()).isZero();

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.RESERVED);
    }

    /**
     * An incomplete checklist delays, it does not cancel. The tenancy activates late carrying its
     * agreed start date — there is no prorating (domain model §5) — so the timer must stay armed
     * rather than be marked fired or disarmed.
     */
    @Test
    void anincompleteChecklistLeavesTheTimerArmedRatherThanCancellingAnything() {
        var tenancyId = reserveStarting(LocalDate.of(2026, 9, 1));
        checklists.addItem(agency, tenancyId, "keys", ChecklistPhase.PRE_ACTIVATION);
        var process = starts();

        assertThat(process.runDue(LocalDate.of(2026, 9, 1)).done()).isZero();
        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.RESERVED);

        checklists.completeItem(agency, tenancyId, "keys");

        assertThat(process.runDue(LocalDate.of(2026, 9, 20)).done()).isEqualTo(1);
        assertThat(Tenancy.from(store.load(tenancyId, "Tenancy").events()).startDate())
            .isEqualTo(LocalDate.of(2026, 9, 1));
    }

    /**
     * Art. 19f ust. 3: a najem instytucjonalny needs the notarial submission declaration, so it
     * may not auto-activate without one. Same shape as an incomplete checklist — delayed, not
     * cancelled — and it was a container-only rule until now.
     */
    @Test
    void aninstitutionalTenancyWithNoNotarialDeclarationDoesNotAutoActivate() {
        var tenancyId = reserveStarting(LocalDate.of(2026, 9, 1), LegalForm.INSTYTUCJONALNY);
        var process = starts();

        assertThat(process.runDue(LocalDate.of(2026, 9, 1)).done()).isZero();

        tenancies.attachDocument(agency, tenancyId, DocType.NOTARIAL_DECLARATION, "s3://akt.pdf",
            null, null, LocalDate.of(2026, 9, 2));

        assertThat(process.runDue(LocalDate.of(2026, 9, 3)).done()).isEqualTo(1);
    }

    /**
     * The rule the whole SweepResult shape exists for. A timer armed against a tenancy that is not
     * there throws, and the sweep must charge that to one subject: everything else in the same run
     * still happens, and the failure is named rather than swallowed.
     */
    @Test
    void oneUnprocessableSubjectCostsExactlyThatSubject() {
        var good = reserveStarting(LocalDate.of(2026, 9, 1));
        var vanished = UUID.randomUUID();
        due.arm(TenancyStartProcess.KIND, vanished, LocalDate.of(2026, 9, 1));

        var result = starts().runDue(LocalDate.of(2026, 9, 1));

        assertThat(result.done()).isEqualTo(1);
        assertThat(result.failed()).containsExactly(vanished);
        assertThat(result.clean()).isFalse();
        assertThat(tenancyRows.tenancy(good).orElseThrow().state()).isEqualTo(Tenancy.State.ACTIVE);
    }

    /**
     * And it reaches somebody. {@code sweep()} rethrows so the scheduler's error handler logs it —
     * a sweep that quietly does nothing every minute forever is the failure mode being avoided.
     */
    @Test
    void thescheduledSweepRethrowsSoAFailedSubjectIsNotSilent() {
        var vanished = UUID.randomUUID();
        due.arm(TenancyStartProcess.KIND, vanished, LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> starts().sweep())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(vanished.toString());
    }

    // ---- RentChangeProcess ---------------------------------------------------------------

    private RentChangeProcess rentChanges(LocalDate today) {
        return new RentChangeProcess(due, tenancies, clockOn(today));
    }

    /** Fires the day before it takes effect (§5), not on the day and not at schedule time. */
    @Test
    void arentChangeIsAppliedTheDayBeforeItTakesEffect() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));
        tenancies.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            ChangeType.AGREED_CHANGE);
        var process = rentChanges(LocalDate.of(2026, 12, 31));

        assertThat(process.runDue(LocalDate.of(2026, 12, 30)).done()).isZero();
        assertThat(process.runDue(LocalDate.of(2026, 12, 31)).done()).isEqualTo(1);

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().monthlyTotal())
            .isEqualByComparingTo("2600");
    }

    /**
     * A queued second change must not be stranded behind the first one's fired timer. Due rows are
     * keyed (kind, subject_id), so there is only ever one, and the sweep has to re-arm it.
     */
    @Test
    void applyingOneChangeArmsTheNextRatherThanStrandingIt() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2028, 8, 31));
        tenancies.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            ChangeType.AGREED_CHANGE);
        tenancies.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 6, 1), new MonthlyAmount(new BigDecimal("2700"), null),
            ChangeType.AGREED_CHANGE);

        rentChanges(LocalDate.of(2026, 12, 31)).runDue(LocalDate.of(2026, 12, 31));

        assertThat(due.armedFor(RentChangeProcess.KIND, tenancyId))
            .contains(LocalDate.of(2027, 5, 31));
        assertThat(rentChanges(LocalDate.of(2027, 5, 31)).runDue(LocalDate.of(2027, 5, 31)).done())
            .isEqualTo(1);
        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().monthlyTotal())
            .isEqualByComparingTo("2700");
    }

    /** A cancelled change leaves nothing to fire, and the timer goes with it. */
    @Test
    void acancelledRentChangeDisarmsItsTimer() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));
        tenancies.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            ChangeType.AGREED_CHANGE);

        tenancies.cancelRentChange(agency, tenancyId, LocalDate.of(2027, 1, 1));

        assertThat(due.armedFor(RentChangeProcess.KIND, tenancyId)).isEmpty();
        assertThat(rentChanges(LocalDate.of(2026, 12, 31)).runDue(LocalDate.of(2026, 12, 31)).done())
            .isZero();
        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().monthlyTotal())
            .isEqualByComparingTo("2500");
    }

    // ---- EndOfTenancyProcess -------------------------------------------------------------

    private EndOfTenancyProcess endings(LocalDate today) {
        return new EndOfTenancyProcess(due, tenancies, clockOn(today));
    }

    @Test
    void atenancyIsFlaggedOneMonthBeforeItsEnd() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));
        var process = endings(LocalDate.of(2027, 7, 31));

        assertThat(process.runDue(LocalDate.of(2027, 7, 30)).done()).isZero();
        assertThat(process.runDue(LocalDate.of(2027, 7, 31)).done()).isEqualTo(1);

        assertThat(Tenancy.from(store.load(tenancyId, "Tenancy").events()).endingSoon()).isTrue();
        assertThat(process.runDue(LocalDate.of(2027, 8, 1)).done()).isZero();
    }

    /**
     * Notice moves the end date, so the prompt moves with it. Warning a manager a month before a
     * date that is no longer the date is the defect this re-arm exists to prevent.
     */
    @Test
    void terminationNoticeMovesThePromptWithTheEndDate() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        tenancies.giveTerminationNotice(agency, tenancyId, "art. 11 ust. 2 pkt 2",
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 30), "s3://notice.pdf");

        assertThat(due.armedFor(EndOfTenancyProcess.KIND, tenancyId))
            .contains(LocalDate.of(2027, 3, 30));
        assertThat(endings(LocalDate.of(2027, 3, 30)).runDue(LocalDate.of(2027, 3, 30)).done())
            .isEqualTo(1);
    }

    /**
     * Ending only ever prompts. A tenancy that reaches its end date and is neither ended nor
     * renewed stays ACTIVE, because in Polish practice it often genuinely continues and a system
     * that ended it would be inventing a legal fact.
     */
    @Test
    void thesweepPromptsAndNeverEndsATenancyItself() {
        var tenancyId = activeTenancyEnding(LocalDate.of(2027, 8, 31));

        endings(LocalDate.of(2027, 9, 30)).runDue(LocalDate.of(2027, 9, 30));

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.ACTIVE);
    }
}
