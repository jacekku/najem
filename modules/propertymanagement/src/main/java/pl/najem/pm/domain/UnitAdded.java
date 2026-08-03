package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record UnitAdded(UUID unitId, UUID propertyId, String name, BigDecimal baseRent) {
}
