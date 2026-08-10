package pl.najem.pm.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.pm.domain.Owner;
import pl.najem.pm.domain.Property;
import pl.najem.pm.domain.Unit;
import pl.najem.pm.domain.UnknownInThisWorkspaceException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PortfolioService} with no database, in milliseconds.
 *
 * <p>The fast half of the pair described in rule 16, and the first one PM's application layer has
 * had — every other test of a service in this module boots a container. {@code PortfolioServiceTest}
 * is the other half and runs the same rules against Postgres.
 *
 * <p><b>When the two disagree, the database is right and this is wrong.</b> That is not a formality:
 * the doubles here model {@code and workspace_id = ?} and a primary key, and a rule that only holds
 * in a HashMap is a rule that does not hold. Anything this proves alone is a hypothesis until
 * {@code PortfolioServiceTest} agrees.
 *
 * <p>Also covers {@code createProperty}'s returned {@link PortfolioService.CreatedProperty}: the id
 * matches the stream it was appended under, and the ownership-share warning travels with it without
 * ever blocking the write.
 */
class PortfolioRulesTest {

    private InMemoryEventStore store;
    private InMemoryPortfolioProjection projection;
    private PortfolioService service;

    private final UUID agency = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        store = new InMemoryEventStore();
        projection = new InMemoryPortfolioProjection();
        service = new PortfolioService(store, projection);
    }

    private UUID unit() {
        return service.addUnit(agency,
            service.createProperty(agency, "Testowa 1", owners()).propertyId(), "M1",
            new BigDecimal("2500"));
    }

    private static List<Owner> owners() {
        return List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")));
    }

    @Test
    void aNewUnitIsStampedWithItsParentsWorkspaceAndStartsAsInventory() {
        var unitId = unit();

        var row = projection.unit(unitId).orElseThrow();
        assertThat(row.workspaceId()).isEqualTo(agency);
        assertThat(row.marketState()).isEqualTo(Unit.MarketState.INVENTORY);
        assertThat(row.baseRent()).isEqualByComparingTo("2500");
    }

    /**
     * The stream and the derived row must say the same name. {@code Unit.add} strips, so passing the
     * caller's raw argument to the projection left pm_unit holding " M2 " while the record said
     * "M2" — two derived tables permanently disagreeing about one unit, since
     * {@code PostgresAttentionListsProjection} joins pm_unit. Asserted against the EVENT rather
     * than against the literal "M2", so this stays true if the normalisation ever changes.
     */
    @Test
    void thederivedRowCarriesTheNameTheEventCarries() {
        var propertyId = service.createProperty(agency, "Testowa 1", owners()).propertyId();

        var unitId = service.addUnit(agency, propertyId, "  M2  ", new BigDecimal("2500"));

        var fromStream = Unit.from(store.load(unitId, "Unit").events());
        assertThat(projection.unit(unitId).orElseThrow().name()).isEqualTo(fromStream.name());
        assertThat(fromStream.name()).isEqualTo("M2");
    }

    @Test
    void marketTransitionsReachBothTheStreamAndTheProjection() {
        var unitId = unit();

        service.openUnitToRent(agency, unitId, "listed");
        assertThat(projection.unit(unitId).orElseThrow().marketState())
            .isEqualTo(Unit.MarketState.OPEN);

        service.closeUnitToRent(agency, unitId, "renovation");
        assertThat(projection.unit(unitId).orElseThrow().marketState())
            .isEqualTo(Unit.MarketState.CLOSED);

        service.removeUnit(agency, unitId, "sold");
        assertThat(projection.unit(unitId).orElseThrow().marketState())
            .isEqualTo(Unit.MarketState.REMOVED);
        assertThat(Unit.from(store.load(unitId, "Unit").events()).marketState())
            .isEqualTo(Unit.MarketState.REMOVED);
    }

    /**
     * The listing reference is written only when it is in the payload. A details update carrying
     * something else must not blank a reference the manager set last week, which is what an
     * unconditional write of {@code details.get("listingRef")} would do.
     */
    @Test
    void adetailsUpdateWithoutAListingRefLeavesTheExistingOneAlone() {
        var unitId = unit();
        service.updateUnitDetails(agency, unitId, Map.of("listingRef", "OLX-99887"));

        service.updateUnitDetails(agency, unitId, Map.of("description", "2 rooms, balcony"));

        assertThat(projection.unit(unitId).orElseThrow().listingRef()).isEqualTo("OLX-99887");
    }

    @Test
    void astrangerCannotTouchAUnitOrHangOneOffAProperty() {
        var propertyId = service.createProperty(agency, "Testowa 1", owners()).propertyId();
        var unitId = service.addUnit(agency, propertyId, "M1", new BigDecimal("2500"));

        assertThatThrownBy(() -> service.addUnit(stranger, propertyId, "M2", new BigDecimal("1")))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.setUnitBaseRent(stranger, unitId, new BigDecimal("1")))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
        assertThatThrownBy(() -> service.removeUnit(stranger, unitId, "mine now"))
            .isInstanceOf(UnknownInThisWorkspaceException.class);
    }

    /**
     * The refusal has to happen before either write. The append and the projection write are two
     * separate statements, so a check placed between them would leave a stream that grew and a row
     * that did not — the exact inconsistency the projection is supposed to be free of.
     */
    @Test
    void arefusedCommandWritesNeitherSide() {
        var unitId = unit();
        long versionBefore = store.load(unitId, "Unit").version();

        assertThatThrownBy(() -> service.setUnitBaseRent(stranger, unitId, new BigDecimal("1")))
            .isInstanceOf(UnknownInThisWorkspaceException.class);

        assertThat(store.load(unitId, "Unit").version()).isEqualTo(versionBefore);
        assertThat(projection.unit(unitId).orElseThrow().baseRent()).isEqualByComparingTo("2500");
    }

    @Test
    void oneAgencysUnitsAreNotAnothers() {
        var mine = unit();
        var theirs = service.addUnit(stranger,
            service.createProperty(stranger, "Cudza 9", owners()).propertyId(), "M1",
            new BigDecimal("2500"));

        assertThat(projection.unitsIn(agency)).extracting(
            InMemoryPortfolioProjection.UnitRow::unitId).containsExactly(mine);
        assertThat(projection.unitsIn(stranger)).extracting(
            InMemoryPortfolioProjection.UnitRow::unitId).containsExactly(theirs);
    }

    /**
     * Property and Unit are separate streams and must stay separate. A store that pooled them would
     * hand a Unit its parent's {@code PropertyCreated} and {@code Unit.from} would throw on an event
     * it does not know — which is why the double partitions and why this asserts that it does.
     */
    @Test
    void thepropertyAndUnitStreamsDoNotBleedIntoEachOther() {
        var propertyId = service.createProperty(agency, "Testowa 1", owners()).propertyId();
        var unitId = service.addUnit(agency, propertyId, "M1", new BigDecimal("2500"));

        assertThat(store.load(propertyId, "Property").events()).hasSize(1);
        assertThat(store.load(unitId, "Unit").events()).hasSize(1);
        assertThat(store.load(unitId, "Property").events()).isEmpty();
    }

    /**
     * The id the caller gets back must be the id the event was appended under. They are produced
     * one line apart from the same variable today, which is exactly the kind of pairing that
     * survives a refactor by luck. Asserting it costs nothing and pins it.
     */
    @Test
    void thereturnedIdIsTheStreamTheEventWasAppendedUnder() {
        var created = service.createProperty(agency, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        var property = Property.from(store.load(created.propertyId(), "Property").events());
        assertThat(property.address()).isEqualTo("Krucza 12");
        assertThat(property.workspaceId()).isEqualTo(agency);
    }

    @Test
    void sharesTotallingOneHundredComeBackWithNoWarnings() {
        var created = service.createProperty(agency, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("50")),
            new Owner(UUID.randomUUID(), new BigDecimal("50"))));

        assertThat(created.warnings()).isEmpty();
    }

    /**
     * The property is created ANYWAY. This is the whole decision the spec records: a half-known
     * share register is a real thing a manager has to be able to write down, and Property's own
     * comment says "soft check — confirm, don't block". A test that only asserted the warning
     * would pass equally well against an implementation that threw.
     */
    @Test
    void sharesTotallingNinetyStillCreateThePropertyAndComeBackWarned() {
        var created = service.createProperty(agency, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("60")),
            new Owner(UUID.randomUUID(), new BigDecimal("30"))));

        assertThat(Property.from(store.load(created.propertyId(), "Property").events()).address())
            .isEqualTo("Krucza 12");
        assertThat(created.warnings()).singleElement().asString().contains("90");
    }

    @Test
    void apropertyWithOneOwnerHoldingEverythingIsClean() {
        var created = service.createProperty(agency, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        assertThat(created.warnings()).isEmpty();
    }
}
