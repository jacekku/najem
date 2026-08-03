package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyTest {

    private final UUID propertyId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID annaId = UUID.randomUUID();

    @Test
    void createdPropertyCarriesWorkspaceAndOwners() {
        var owners = List.of(new Owner(annaId, new BigDecimal("100")));

        var events = Property.create(propertyId, workspaceId, "Testowa 1, Kraków", owners);

        assertThat(events).containsExactly(
            new PropertyEvents.PropertyCreated(workspaceId, propertyId, "Testowa 1, Kraków", owners));
        assertThat(Property.from(events).workspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void sharesNotTotalling100AreWarnedNotRejected() {
        var owners = List.of(new Owner(annaId, new BigDecimal("60")));

        var property = Property.from(Property.create(propertyId, workspaceId, "Testowa 1", owners));

        assertThat(property.warnings().messages())
            .containsExactly("Ownership shares total 60, expected 100");
    }

    @Test
    void ownershipChangeReplacesTheOwnerSetWithoutTouchingTheWorkspace() {
        var fundacja = UUID.randomUUID();
        var history = new java.util.ArrayList<>(Property.create(propertyId, workspaceId, "Testowa 1",
            List.of(new Owner(annaId, new BigDecimal("100")))));

        history.addAll(Property.from(history)
            .changeOwnership(List.of(new Owner(fundacja, new BigDecimal("100")))));

        var property = Property.from(history);
        assertThat(property.owners()).extracting(Owner::contactId).containsExactly(fundacja);
        assertThat(property.workspaceId()).isEqualTo(workspaceId);
    }
}
