package pl.najem.pm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Tenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenancyBoardProjection} with no database — the register the Najmy screen reads, and the
 * party list that was not projected anywhere until it existed.
 *
 * <p>{@code TenancyBoardQueryTest} runs the same questions against Postgres and wins when the two
 * disagree (rule 16). What this tier is for is the cases that are tedious to set up against a
 * container and cheap here: a tenant added and removed again, a cancelled reservation, a foreign
 * workspace asking.
 */
class TenancyRegisterTest {

    private InMemoryEventStore store;
    private InMemoryPortfolioProjection portfolioRows;
    private InMemoryTenancies tenancyRows;
    private PortfolioService portfolio;
    private TenancyService service;

    private final UUID agency = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private UUID unitId;

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        portfolioRows = new InMemoryPortfolioProjection();
        tenancyRows = new InMemoryTenancies(portfolioRows);
        portfolio = new PortfolioService(store, portfolioRows);
        service = new TenancyService(store, tenancyRows, new InMemoryProcessDue());
        unitId = unitIn(agency);
    }

    /**
     * The whole reason pm_tenancy_party exists: a reserved tenancy names its tenants, and the
     * register can say who they are without rebuilding an aggregate per row.
     */
    @Test
    void areservationRecordsEveryTenantItNames() {
        var anna = UUID.randomUUID();
        var karol = UUID.randomUUID();
        var tenancyId = reserve(List.of(anna, karol), List.of());

        assertThat(tenancyRows.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.tenancyId()).isEqualTo(tenancyId);
                assertThat(row.tenantContactIds()).containsExactlyInAnyOrder(anna, karol);
            });
    }

    /**
     * Guarantors are stored and are NOT tenants. Asserted because one table holds both and the only
     * thing separating them is the role column — a write that dropped the role, or read the wrong
     * one, would put a guarantor's name in the Najemca column, which is a claim about who owes the
     * rent.
     */
    @Test
    void aguarantorIsNotListedAsATenant() {
        var tenant = UUID.randomUUID();
        var guarantor = UUID.randomUUID();
        reserve(List.of(tenant), List.of(guarantor));

        assertThat(tenancyRows.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> assertThat(row.tenantContactIds()).containsExactly(tenant));
    }

    /**
     * The omission this whole change closes. Both commands appended to the stream and wrote no
     * projection at all, so the register would have shown the reservation's original tenants
     * forever — silently, and only for tenancies somebody had edited.
     */
    @Test
    void atenantAddedAndOneRemovedBothReachTheRegister() {
        var anna = UUID.randomUUID();
        var karol = UUID.randomUUID();
        var tenancyId = reserve(List.of(anna), List.of());

        service.addTenant(agency, tenancyId, karol);
        assertThat(tenants(tenancyId)).containsExactlyInAnyOrder(anna, karol);

        service.removeTenant(agency, tenancyId, anna);
        assertThat(tenants(tenancyId)).containsExactly(karol);
    }

    /** A called-off reservation never let the unit and is not part of the register. */
    @Test
    void acancelledReservationLeavesTheRegister() {
        var tenancyId = reserve(List.of(UUID.randomUUID()), List.of());
        assertThat(tenancyRows.forWorkspace(agency)).hasSize(1);

        service.cancelReservation(agency, tenancyId, "najemca się rozmyślił");

        assertThat(tenancyRows.forWorkspace(agency)).isEmpty();
    }

    /**
     * An ended tenancy stays. The opposite of the case above and asserted beside it, because one
     * predicate decides both — {@code state <> 'CANCELLED'} — and a change that excluded ENDED too
     * would tell a manager a unit had never been let. That is the same conflation V64 had to undo
     * in reporting's occupancy.
     */
    @Test
    void anendedTenancyStaysOnTheRegister() {
        var tenancyId = reserve(List.of(UUID.randomUUID()), List.of());
        service.activate(agency, tenancyId, LocalDate.of(2026, 9, 1));
        service.end(agency, tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 8, 31), EndReason.AGREEMENT_EXPIRY, null, true));

        assertThat(tenancyRows.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> assertThat(row.state()).isEqualTo(Tenancy.State.ENDED));
    }

    /**
     * ERROR_ANNULLED is not an ending, and the register must not list it as one.
     *
     * <p>This is the case that found the defect. {@code TenancyProjection.ended} took only the id
     * and the workspace, so pm_tenancy recorded every ending identically and a tenancy that should
     * never have existed sat in the register as an ordinary finished one — a claim that somebody
     * lived in a unit that was never let. Reporting had already drawn this line for occupancy
     * (V64); PM could not, until V20260810120100 gave it the column.
     *
     * <p>Asserted beside {@code anendedTenancyStaysOnTheRegister} on purpose. The two differ only
     * in the reason, so together they pin that the predicate discriminates on the reason and not on
     * the state — a filter that dropped all ENDED rows would satisfy this test alone.
     */
    @Test
    void anannulledTenancyIsNotOnTheRegisterAtAll() {
        var tenancyId = reserve(List.of(UUID.randomUUID()), List.of());
        service.activate(agency, tenancyId, LocalDate.of(2026, 9, 1));
        service.end(agency, tenancyId, new EndTenancy(LocalDate.of(2026, 9, 30), null,
            EndReason.ERROR_ANNULLED, "aktywowany przez pomyłkę", true));

        assertThat(tenancyRows.forWorkspace(agency)).isEmpty();
    }

    /** Workspace is the hard boundary, and it is a filter rather than a failure. */
    @Test
    void astrangerSeesNoneOfTheAgencysTenancies() {
        reserve(List.of(UUID.randomUUID()), List.of());

        assertThat(tenancyRows.forWorkspace(stranger)).isEmpty();
    }

    /**
     * The unit and property that name the row come from an INNER join, so a tenancy the portfolio
     * cannot place is absent rather than rendered with a blank address. Same rule the attention
     * lists run under, asserted here because this is a second port relying on it.
     */
    @Test
    void thelokalColumnCarriesTheAddressAndTheUnitName() {
        reserve(List.of(UUID.randomUUID()), List.of());

        assertThat(tenancyRows.forWorkspace(agency))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.propertyAddress()).isEqualTo("Testowa 1, Kraków");
                assertThat(row.unitName()).isEqualTo("M1");
            });
    }

    // ----------------------------------------------------------------------------------------
    // forTenancy — the single-contract read the unit screen holds
    // ----------------------------------------------------------------------------------------

    /**
     * The whole contract, including the four facts the register's own row does not carry: the legal
     * form, the payment day, the deposit and the reference.
     *
     * <p>Each is asserted separately rather than as one record equality, because a record built with
     * two adjacent same-typed arguments transposed still equals nothing but itself — naming the
     * fields is what makes a swapped {@code rentDay}/{@code depositAmount} pair fail here.
     */
    @Test
    void forTenancyCarriesTheWholeContractAndNotJustTheRegistersColumns() {
        var anna = UUID.randomUUID();
        var poreczyciel = UUID.randomUUID();
        var tenancyId = reserve(List.of(anna), List.of(poreczyciel), LegalForm.OKAZJONALNY);

        assertThat(tenancyRows.forTenancy(agency, tenancyId))
            .get()
            .satisfies(row -> {
                assertThat(row.legalForm()).isEqualTo(LegalForm.OKAZJONALNY);
                assertThat(row.rentDay()).isEqualTo(10);
                assertThat(row.monthlyTotal()).isEqualByComparingTo("2500");
                assertThat(row.paymentReference()).startsWith("NAJEM/");
                assertThat(row.unitName()).isEqualTo("M1");
                assertThat(row.propertyAddress()).isEqualTo("Testowa 1, Kraków");
                assertThat(row.tenantContactIds()).containsExactly(anna);
                assertThat(row.guarantorContactIds()).containsExactly(poreczyciel);
            });
    }

    /**
     * A contract with no declared component split says so, and its three components stay null.
     *
     * <p>V22 records that "no split" and "a split with a zero admin fee" are legally different. A
     * read that coalesced the nulls to zero would erase the distinction the flag exists to carry,
     * and the screen would render "Czynsz bazowy 0,00" for a perfectly ordinary contract.
     */
    @Test
    void acontractWithNoSplitReportsNullComponentsRatherThanZeroes() {
        var tenancyId = reserve(List.of(UUID.randomUUID()), List.of());

        assertThat(tenancyRows.forTenancy(agency, tenancyId))
            .get()
            .satisfies(row -> {
                assertThat(row.componentSplit()).isFalse();
                assertThat(row.rent()).isNull();
                assertThat(row.adminFee()).isNull();
                assertThat(row.mediaAdvance()).isNull();
            });
    }

    /**
     * The four ways this read answers empty, asserted together because they are ONE predicate set.
     *
     * <p>Unknown, foreign, cancelled and error-annulled must be indistinguishable: an id that
     * answered differently for a tenancy in another agency would confirm that it exists, which is
     * the disclosure every undifferentiated read here exists to prevent. And the two exclusions must
     * match {@code forWorkspace}'s exactly — the adapter shares one {@code SCOPE} string for that
     * reason, and this is the assertion that notices if a future edit gives one read its own.
     */
    @Test
    void forTenancyIsEmptyForUnknownForeignCancelledAndAnnulledAlike() {
        assertThat(tenancyRows.forTenancy(agency, UUID.randomUUID()))
            .as("never created").isEmpty();

        var mine = reserve(List.of(UUID.randomUUID()), List.of());
        assertThat(tenancyRows.forTenancy(stranger, mine)).as("another agency asking").isEmpty();

        service.cancelReservation(agency, mine, "najemca się rozmyślił");
        assertThat(tenancyRows.forTenancy(agency, mine)).as("called off").isEmpty();

        var annulled = reserve(List.of(UUID.randomUUID()), List.of());
        service.activate(agency, annulled, LocalDate.of(2026, 9, 1));
        service.end(agency, annulled, new EndTenancy(LocalDate.of(2026, 9, 30), null,
            EndReason.ERROR_ANNULLED, "aktywowany przez pomyłkę", true));
        assertThat(tenancyRows.forTenancy(agency, annulled)).as("should never have existed")
            .isEmpty();
    }

    /** An ended tenancy is still readable — it ran, and its contract is what a manager looks up. */
    @Test
    void anendedTenancyIsStillReadableInFull() {
        var tenancyId = reserve(List.of(UUID.randomUUID()), List.of());
        service.activate(agency, tenancyId, LocalDate.of(2026, 9, 1));
        service.end(agency, tenancyId, new EndTenancy(LocalDate.of(2027, 8, 31),
            LocalDate.of(2027, 8, 31), EndReason.AGREEMENT_EXPIRY, null, true));

        assertThat(tenancyRows.forTenancy(agency, tenancyId))
            .get()
            .satisfies(row -> assertThat(row.state()).isEqualTo(Tenancy.State.ENDED));
    }

    private List<UUID> tenants(UUID tenancyId) {
        return tenancyRows.forWorkspace(agency).stream()
            .filter(row -> row.tenancyId().equals(tenancyId))
            .findFirst().orElseThrow()
            .tenantContactIds();
    }

    private UUID unitIn(UUID workspaceId) {
        var propertyId = portfolio.createProperty(workspaceId, "Testowa 1, Kraków",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        return portfolio.addUnit(workspaceId, propertyId, "M1", new BigDecimal("2500"));
    }

    private UUID reserve(List<UUID> tenants, List<UUID> guarantors) {
        return reserve(tenants, guarantors, LegalForm.ZWYKLY);
    }

    /**
     * The form is a parameter because {@code ZWYKLY} is the enum's FIRST constant, so a projection
     * that hardcoded a default, or read the wrong column into it, would still satisfy an assertion
     * made against a tenancy reserved as zwykły. One test reserves an okazjonalny one for exactly
     * that reason (refactoring rule 13 — a check that cannot fail is not a check).
     */
    private UUID reserve(List<UUID> tenants, List<UUID> guarantors, LegalForm form) {
        return service.reserve(agency, new ReserveTenancy(null, null, unitId, tenants, guarantors,
            LocalDate.of(2026, 9, 1), new Term.FixedTerm(LocalDate.of(2027, 8, 31)),
            form, new MonthlyAmount(new BigDecimal("2500"), null), 10, null,
            "NAJEM/" + UUID.randomUUID())).tenancyId();
    }
}
