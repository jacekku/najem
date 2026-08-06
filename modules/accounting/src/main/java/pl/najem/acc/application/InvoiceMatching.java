package pl.najem.acc.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Finding the obligation a bank line might be paying, one rung of the ladder at a time.
 *
 * <p>Separate from {@link InvoiceRepository} because it answers a different kind of question. That
 * port is about obligations a tenancy carries — what is open, what was asserted. This one is about
 * evidence: given what a payer typed, which charge does it name? Each method is one rung, and the
 * order they are tried in is {@link IngestionService}'s, not this interface's.
 *
 * <p>Every rung considers only charges that are open and not withdrawn. Money cannot be suggested
 * against something already settled or billed in error.
 */
public interface InvoiceMatching {

    /**
     * Tier 1 — the reference and the amount both name an open charge. Oldest first when several do,
     * which only happens when identical charges exist and either would be a defensible answer.
     */
    Optional<UUID> byExactReferenceAndAmount(UUID workspaceId, String reference, BigDecimal amount);

    /**
     * Tier 2 — the charge's reference appears somewhere in what the payer typed, once both are
     * stripped of case and separators. The amount is deliberately not constrained: a part payment is
     * still that tenant's money.
     *
     * <p>The <em>longest</em> matching reference wins, not the oldest charge. A short reference is a
     * substring of a longer one, so a payer naming {@code NAJEM/M1/2027/09} also literally names
     * {@code NAJEM/M1}; oldest-first would suggest a small January charge against a full September
     * payment, and the tier badge would read as mild uncertainty rather than as the wrong month.
     *
     * @param normalisedTitle the payer's text, already stripped. A reference that normalises to
     *                        nothing must match no charge rather than every charge.
     */
    Optional<UUID> byReferenceWithin(UUID workspaceId, String normalisedTitle);

    /** Tier 3's second half — the oldest thing this tenancy still owes. */
    Optional<UUID> oldestOpenOf(UUID workspaceId, UUID tenancyId);
}
