package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Co-owner of a property. Identified by ContactId only — no PII in events. */
public record Owner(UUID contactId, BigDecimal sharePercent) {
}
