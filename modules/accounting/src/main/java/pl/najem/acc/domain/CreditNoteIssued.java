package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The document face of a reversal on an already-paid charge: rent deductions, outage compensation.
 * The original charge stands; the pair is the audit trail.
 */
public record CreditNoteIssued(UUID creditNoteId, UUID chargeId, UUID tenancyId, BigDecimal amount,
                               String reason, LocalDate issuedOn) {
}
