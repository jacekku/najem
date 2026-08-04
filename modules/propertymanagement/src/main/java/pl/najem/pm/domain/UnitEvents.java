package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Unit-stream events. The tenancy calendar events land in Task 3. */
public final class UnitEvents {

    private UnitEvents() {
    }

    public record UnitAddedToProperty(UUID workspaceId, UUID unitId, UUID propertyId,
                                      String name, BigDecimal baseRent) {
    }

    public record UnitBaseRentSet(UUID workspaceId, UUID unitId, BigDecimal baseRent) {
    }

    /** Non-domain catch-all: photos, description, amenities, OLX/Otodom listing ref. */
    public record UnitDetailsUpdated(UUID workspaceId, UUID unitId, Map<String, String> details) {
    }

    public record UnitOpenedToRent(UUID workspaceId, UUID unitId, String reason) {
    }

    public record UnitClosedToRent(UUID workspaceId, UUID unitId, String reason) {
    }

    public record UnitRemovedFromProperty(UUID workspaceId, UUID unitId, String reason) {
    }
}
