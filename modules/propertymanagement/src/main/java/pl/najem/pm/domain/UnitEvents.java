package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Unit-stream events. The rest of the set (market state, calendar) lands in Tasks 2 and 3. */
public final class UnitEvents {

    private UnitEvents() {
    }

    public record UnitAddedToProperty(UUID workspaceId, UUID unitId, UUID propertyId,
                                      String name, BigDecimal baseRent) {
    }
}
