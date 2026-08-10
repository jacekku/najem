package pl.najem.pm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.DocType;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.OverlappingTenancyException;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Term;
import pl.najem.pm.domain.Unit;
import pl.najem.pm.domain.TenancyPeriod;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TenancyService} and {@link ChecklistService} with no database. {@code TenancyServiceTest},
 * {@code TenancyReservationTest} and the process-manager suites run the same rules against Postgres
 * and win when the two disagree.
 */
class TenancyRulesTest {

    private InMemoryEventStore store;
    private InMemoryPortfolioProjection portfolioRows;
    private InMemoryTenancies tenancyRows;
    private InMemoryProcessDue due;
    private PortfolioService portfolio;
    private TenancyService service;
    private ChecklistService checklists;
    private AttentionListsService attention;

    private final UUID agency = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private UUID unitId;

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        portfolioRows = new InMemoryPortfolioProjection();
        tenancyRows = new InMemoryTenancies(portfolioRows);
        due = new InMemoryProcessDue();
        portfolio = new PortfolioService(store, portfolioRows);
        service = new TenancyService(store, tenancyRows, due);
        checklists = new ChecklistService(store);
        attention = new AttentionListsService(tenancyRows, new InMemoryRepairs());
        unitId = unitIn(agency);
    }

    private UUID unitIn(UUID workspaceId) {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        return portfolio.addUnit(workspaceId, propertyId, "M1", new BigDecimal("2500"));
    }

    private UUID reserve() {
        return service.reserve(agency, command(unitId, LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(LocalDate.of(2027, 8, 31)))).tenancyId();
    }

    private UUID activeTenancy() {
        var tenancyId = reserve();
        service.activate(agency, tenancyId, LocalDate.of(2026, 9, 1));
        return tenancyId;
    }

    private static ReserveTenancy command(UUID unitId, LocalDate start, Term term) {
        return new ReserveTenancy(null, null, unitId, List.of(UUID.randomUUID()), List.of(),
            start, term, LegalForm.ZWYKLY, new MonthlyAmount(new BigDecimal("2500"), null),
            10, null, "NAJEM/" + UUID.randomUUID());
    }

    /** The child never takes a caller-supplied workspace: the unit says what it inherits. */
    @Test
    void atenancyInheritsTheWorkspaceOfTheUnitItIsReservedOn() {
        var tenancyId = reserve();

        assertThat(Tenancy.from(store.load(tenancyId, "Tenancy").events()).workspaceId())
            .isEqualTo(agency);
        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().workspaceId()).isEqualTo(agency);
    }

    @Test
    void astrangerCannotReserveOnSomeoneElsesUnit() {
        assertThatThrownBy(() -> service.reserve(stranger,
            command(unitId, LocalDate.of(2026, 9, 1), new Term.Indefinite())))
            .isInstanceOf(UnknownInThisWorkspaceException.class);

        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods()).isEmpty();
    }

    /**
     * Every manager command, one caller who owns nothing. The point is coverage of the surface
     * rather than of one route: the gap this replaces was an endpoint nobody had checked, so a test
     * naming three of thirteen commands would have reproduced it.
     */
    @Test
    void nocommandOnSomeoneElsesTenancyIsAccepted() {
        var tenancyId = activeTenancy();

        assertThatThrownBy(() -> service.cancelReservation(stranger, tenancyId, "x"))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.activate(stranger, tenancyId, LocalDate.of(2026, 9, 1)))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.addTenant(stranger, tenancyId, UUID.randomUUID()))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.removeTenant(stranger, tenancyId, UUID.randomUUID()))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.scheduleRentChange(stranger, tenancyId,
            LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 1),
            new MonthlyAmount(new BigDecimal("9999"), null), ChangeType.AGREED_CHANGE))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.cancelRentChange(stranger, tenancyId,
            LocalDate.of(2027, 1, 1))).isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.giveTerminationNotice(stranger, tenancyId, "art. 11",
            LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 30), null))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.end(stranger, tenancyId, new EndTenancy(
            LocalDate.of(2027, 8, 31), null, EndReason.TENANT_NOTICE, "", true)))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.addComment(stranger, tenancyId, "mine now"))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.correctDetails(stranger, tenancyId, Map.of("rentDay", "5")))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.attachDocument(stranger, tenancyId,
            DocType.INSURANCE_POLICY, "s3://x", LocalDate.of(2026, 9, 1),
            LocalDate.of(2027, 8, 31), LocalDate.of(2026, 8, 20)))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.warnings(stranger, tenancyId))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> checklists.addItem(stranger, tenancyId, "keys",
            ChecklistPhase.PRE_ACTIVATION)).isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> checklists.completeItem(stranger, tenancyId, "keys"))
            .isInstanceOf(UnknownInThisWorkspaceException.class);

        // Nothing was half-done: the refusal is before the append, not after it.
        assertThat(Tenancy.from(store.load(tenancyId, "Tenancy").events()).state())
            .isEqualTo(Tenancy.State.ACTIVE);
    }

    /**
     * The sweep methods take no workspace on purpose — there is no caller — so this pins that they
     * still work, and that nothing was made unreachable by pushing the check into the commands.
     */
    @Test
    void thetimerCanActivateATenancyWithNoCallerAtAll() {
        var tenancyId = reserve();
        assertThat(due.armedFor(TenancyStartProcess.KIND, tenancyId))
            .contains(LocalDate.of(2026, 9, 1));

        assertThat(service.activateIfDue(tenancyId)).isTrue();

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.ACTIVE);
    }

    @Test
    void cancellingAReservationFreesTheUnitAndDisarmsTheStartTimer() {
        var tenancyId = reserve();

        service.cancelReservation(agency, tenancyId, "never signed");

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.CANCELLED);
        assertThat(due.armedFor(TenancyStartProcess.KIND, tenancyId)).isEmpty();
        assertThat(service.reserve(agency, command(unitId, LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(LocalDate.of(2027, 8, 31)))).tenancyId()).isNotNull();
    }

    @Test
    void areservedTenancyIsReservedUntilItIsCancelled() {
        var tenancyId = reserve();
        assertThat(service.isReserved(agency, tenancyId)).isTrue();

        service.cancelReservation(agency, tenancyId, "pomyłka");
        assertThat(service.isReserved(agency, tenancyId)).isFalse();
    }

    @Test
    void anactivatedTenancyIsNoLongerReserved() {
        var tenancyId = activeTenancy();

        assertThat(service.isReserved(agency, tenancyId)).isFalse();
    }

    @Test
    void asecondOverlappingReservationOnOneUnitIsRefused() {
        reserve();

        assertThatThrownBy(() -> service.reserve(agency, command(unitId, LocalDate.of(2027, 1, 1),
            new Term.FixedTerm(LocalDate.of(2027, 12, 31)))))
            .isInstanceOf(OverlappingTenancyException.class);
    }

    /**
     * The refusal names the period that blocks, not only that something did.
     *
     * <p>A screen has to tell the manager when the unit frees up, and the alternatives to carrying
     * the period are parsing the message back apart or asking the unit a second question and hoping
     * the answer has not moved.
     */
    @Test
    void arefusedReservationSaysWhichPeriodBlockedIt() {
        var blocker = reserve();

        assertThatThrownBy(() -> service.reserve(agency, command(unitId, LocalDate.of(2027, 1, 1),
            new Term.FixedTerm(LocalDate.of(2027, 12, 31)))))
            .isInstanceOfSatisfying(OverlappingTenancyException.class, e -> {
                assertThat(e.blocking().tenancyId()).isEqualTo(blocker);
                assertThat(e.blocking().start()).isEqualTo(LocalDate.of(2026, 9, 1));
                assertThat(e.blocking().end()).isEqualTo(LocalDate.of(2027, 8, 31));
            });
    }

    /** An indefinite blocker has a null end, which means the unit does not free up at all. */
    @Test
    void anindefiniteBlockerReportsNoEndDate() {
        service.reserve(agency, command(unitId, LocalDate.of(2026, 9, 1), new Term.Indefinite()));

        assertThatThrownBy(() -> service.reserve(agency, command(unitId, LocalDate.of(2030, 1, 1),
            new Term.FixedTerm(LocalDate.of(2030, 12, 31)))))
            .isInstanceOfSatisfying(OverlappingTenancyException.class, e ->
                assertThat(e.blocking().end()).isNull());
    }

    @Test
    void thebookedPeriodsOfAUnitAreListedEarliestFirst() {
        var second = service.reserve(agency, command(unitId, LocalDate.of(2028, 1, 1),
            new Term.FixedTerm(LocalDate.of(2028, 12, 31)))).tenancyId();
        var first = reserve();

        assertThat(service.periodsOf(agency, unitId))
            .extracting(TenancyPeriod::tenancyId)
            .containsExactly(first, second);
    }

    /**
     * A cancelled reservation leaves no slot behind — which is what makes this list safe to show as
     * "already taken" without filtering anything out of it.
     */
    @Test
    void acancelledReservationLeavesNoBookedPeriod() {
        var tenancyId = reserve();
        service.cancelReservation(agency, tenancyId, "pomyłka");

        assertThat(service.periodsOf(agency, unitId)).isEmpty();
    }

    @Test
    void thebookedPeriodsOfAnotherAgencysUnitAreRefused() {
        reserve();

        assertThatThrownBy(() -> service.periodsOf(UUID.randomUUID(), unitId))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /**
     * One timer per (kind, subject). Scheduling a later change after an earlier one must leave the
     * EARLIER one armed — arming this one would overwrite the earlier row and strand it, which is
     * the whole reason {@code armNextRentChange} exists rather than a bare {@code arm}.
     */
    @Test
    void alaterRentChangeDoesNotStealTheTimerFromAnEarlierOne() {
        var tenancyId = activeTenancy();
        service.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            ChangeType.AGREED_CHANGE);

        service.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 6, 1), new MonthlyAmount(new BigDecimal("2700"), null),
            ChangeType.AGREED_CHANGE);

        assertThat(due.armedFor(RentChangeProcess.KIND, tenancyId))
            .contains(LocalDate.of(2026, 12, 31));
    }

    /** The applied rent reaches the projection; a queued later change keeps its own timer. */
    @Test
    void applyingADueRentChangeUpdatesTheProjectionAndArmsTheNextOne() {
        var tenancyId = activeTenancy();
        service.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            ChangeType.AGREED_CHANGE);
        service.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 6, 1), new MonthlyAmount(new BigDecimal("2700"), null),
            ChangeType.AGREED_CHANGE);

        assertThat(service.applyDueRentChange(tenancyId, LocalDate.of(2026, 12, 31))).isTrue();

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().monthlyTotal())
            .isEqualByComparingTo("2600");
        assertThat(due.armedFor(RentChangeProcess.KIND, tenancyId))
            .contains(LocalDate.of(2027, 5, 31));
    }

    @Test
    void endingATenancyFreesTheCalendarAndDisarmsEveryTimerPointedAtIt() {
        var tenancyId = activeTenancy();
        service.scheduleRentChange(agency, tenancyId, LocalDate.of(2026, 9, 2),
            LocalDate.of(2027, 1, 1), new MonthlyAmount(new BigDecimal("2600"), null),
            ChangeType.AGREED_CHANGE);

        service.end(agency, tenancyId, new EndTenancy(LocalDate.of(2026, 12, 1),
            LocalDate.of(2026, 12, 3), EndReason.TENANT_NOTICE, "", false));

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().state())
            .isEqualTo(Tenancy.State.ENDED);
        assertThat(due.armedFor(RentChangeProcess.KIND, tenancyId)).isEmpty();
        assertThat(due.armedFor(TenancyStartProcess.KIND, tenancyId)).isEmpty();
        assertThat(due.armedFor(EndOfTenancyProcess.KIND, tenancyId)).isEmpty();
        assertThat(due.armedFor(EndOfTenancyProcess.DEPOSIT_SETTLEMENT_KIND, tenancyId))
            .contains(LocalDate.of(2027, 1, 3));
        assertThat(Unit.from(store.load(unitId, "Unit").events()).periods()).isEmpty();
    }

    /** The aggregate decides which policy is current; the projection copies its answer. */
    @Test
    void attachingAPolicyPutsItsExpiryOnTheAttentionList() {
        var tenancyId = activeTenancy();

        service.attachDocument(agency, tenancyId, DocType.INSURANCE_POLICY, "s3://oc.pdf",
            LocalDate.of(2026, 9, 1), LocalDate.of(2027, 3, 31), LocalDate.of(2026, 8, 20));

        assertThat(attention.insuranceExpiring(agency, LocalDate.of(2027, 3, 1)))
            .extracting(TenancyAttentionRow::tenancyId).containsExactly(tenancyId);
        assertThat(attention.insuranceExpiring(agency, LocalDate.of(2027, 1, 1))).isEmpty();
    }

    /**
     * The window is one month and it belongs here, not in the query (rule 10). These two assertions
     * are what would move if it were ever pushed into SQL.
     */
    @Test
    void theendingSoonListReachesExactlyOneMonthAhead() {
        var tenancyId = activeTenancy();

        assertThat(attention.endingSoon(agency, LocalDate.of(2027, 7, 31)))
            .extracting(TenancyAttentionRow::tenancyId).containsExactly(tenancyId);
        assertThat(attention.endingSoon(agency, LocalDate.of(2027, 7, 30))).isEmpty();
    }

    @Test
    void oneAgencysAttentionListsAreNotAnothers() {
        activeTenancy();
        var theirUnit = unitIn(stranger);
        var theirs = service.reserve(stranger, command(theirUnit, LocalDate.of(2026, 9, 1),
            new Term.FixedTerm(LocalDate.of(2027, 8, 31)))).tenancyId();

        assertThat(attention.startingSoon(stranger, LocalDate.of(2026, 8, 15)))
            .extracting(TenancyAttentionRow::tenancyId).containsExactly(theirs);
        assertThat(attention.startingSoon(agency, LocalDate.of(2026, 8, 15)))
            .extracting(TenancyAttentionRow::tenancyId).doesNotContain(theirs);
        assertThat(attention.endingSoon(stranger, LocalDate.of(2027, 8, 1))).isEmpty();
    }

    /**
     * Rule 12. {@code pm_tenancy.state} used to be written as four SQL string literals; the
     * projection now binds {@code Tenancy.State.name()}. That is better — one definition — and it
     * silently turns renaming an enum constant into a data migration. This is what tells the two
     * apart, and it is the only thing standing between a rename and rows nobody can read.
     */
    @Test
    void thestoredStateNamesAreWireValues() {
        assertThat(Tenancy.State.values()).extracting(Enum::name)
            .containsExactly("RESERVED", "ACTIVE", "CANCELLED", "ENDED");
    }

    /** Corrections reach the projection, and the warning they raise reaches the manager. */
    @Test
    void correctingAPublishedFactUpdatesTheRowAndWarns() {
        var tenancyId = activeTenancy();

        var warnings = service.correctDetails(agency, tenancyId,
            Map.of("paymentReference", "NAJEM/CORRECTED"));

        assertThat(tenancyRows.tenancy(tenancyId).orElseThrow().paymentReference())
            .isEqualTo("NAJEM/CORRECTED");
        assertThat(warnings).isNotEmpty();
    }
}
