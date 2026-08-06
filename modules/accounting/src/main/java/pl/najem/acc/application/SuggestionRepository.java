package pl.najem.acc.application;

import pl.najem.acc.domain.MatchTier;

import java.util.Optional;
import java.util.UUID;

/**
 * The matches the ladder has proposed and nobody has decided yet.
 *
 * <p>A record, not a projection: a suggestion carries the tier it was found at, which is a judgement
 * made against the evidence available when the payment arrived. Re-deriving it later from today's
 * charges would answer a different question.
 */
public interface SuggestionRepository {

    /**
     * The invoice suggested for this payment, if one was.
     *
     * <p>Empty is an ordinary answer and covers two cases that are the same from here: the ladder
     * found nothing, and the payment belongs to another workspace. Another agency's payment is
     * absent rather than forbidden, so there is nothing to confirm and nothing happens.
     */
    Optional<UUID> suggestedInvoice(UUID workspaceId, UUID paymentId);

    /**
     * Records the rung that answered, alongside the match itself. The tier is stored because a
     * manager deciding on a suggestion needs to know whether the module was certain or guessing —
     * a suggestion without its tier is an assertion with its evidence thrown away.
     */
    void suggest(UUID workspaceId, UUID paymentId, UUID invoiceId, MatchTier tier);
}
