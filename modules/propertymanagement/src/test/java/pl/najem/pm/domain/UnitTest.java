package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnitTest {

    private final UUID unitId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private List<Object> added() {
        return new ArrayList<>(Unit.add(unitId, workspaceId, propertyId, "M1", new BigDecimal("2500")));
    }

    @Test
    void newUnitIsInventoryUntilOpenedToRent() {
        assertThat(Unit.from(added()).marketState()).isEqualTo(Unit.MarketState.INVENTORY);
    }

    @Test
    void openingAndClosingAreFreeTransitionsCarryingAReason() {
        var history = added();
        history.addAll(Unit.from(history).openToRent("listed on OLX"));
        assertThat(Unit.from(history).marketState()).isEqualTo(Unit.MarketState.OPEN);

        history.addAll(Unit.from(history).closeToRent("renovation"));

        assertThat(Unit.from(history).marketState()).isEqualTo(Unit.MarketState.CLOSED);
        assertThat(history).last()
            .isEqualTo(new UnitEvents.UnitClosedToRent(workspaceId, unitId, "renovation"));
    }

    @Test
    void closedUnitCanBeReopenedAndThereIsNoTransitionWall() {
        var history = added();
        history.addAll(Unit.from(history).closeToRent("construction"));
        history.addAll(Unit.from(history).openToRent("construction finished"));
        history.addAll(Unit.from(history).closeToRent("cleanup"));

        assertThat(Unit.from(history).marketState()).isEqualTo(Unit.MarketState.CLOSED);
    }

    @Test
    void baseRentIsTheNegotiationAnchorAndCanMoveEitherWay() {
        var history = added();
        history.addAll(Unit.from(history).setBaseRent(new BigDecimal("2400")));
        assertThat(Unit.from(history).baseRent()).isEqualByComparingTo("2400");

        history.addAll(Unit.from(history).setBaseRent(new BigDecimal("2700")));
        assertThat(Unit.from(history).baseRent()).isEqualByComparingTo("2700");
    }

    @Test
    void listingReferenceIsCarriedInTheNonDomainDetailsCatchAll() {
        var history = added();
        history.addAll(Unit.from(history).updateDetails(Map.of("listingRef", "OLX-99887",
            "description", "2 rooms, balcony")));

        assertThat(Unit.from(history).listingRef()).isEqualTo("OLX-99887");
    }

    @Test
    void removalIsHowSplitsAndMergesAreHandled() {
        var history = added();
        history.addAll(Unit.from(history).remove("split into M1a and M1b"));

        assertThat(Unit.from(history).marketState()).isEqualTo(Unit.MarketState.REMOVED);
    }

    @Test
    void everyUnitEventCarriesTheWorkspaceOfItsProperty() {
        var history = added();
        var unit = Unit.from(history);
        history.addAll(unit.openToRent("listed"));
        history.addAll(Unit.from(history).setBaseRent(new BigDecimal("2400")));

        assertThat(history).allSatisfy(event ->
            assertThat(event.toString()).contains(workspaceId.toString()));
    }
}
