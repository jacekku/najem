package pl.najem.acc.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the module has learned about which account pays for which tenancy.
 *
 * <p>Learned from confirmations only. A confirmed match is the one trustworthy statement that this
 * account pays for this tenancy, because a human looked at it — which is why tier 3 reads this
 * rather than anything ingestion guessed.
 */
public interface PayerAccountRepository {

    /**
     * Every tenancy this account is known to pay for, in the order they were learned.
     *
     * <p>Several is ordinary — a parent guaranteeing two children's flats — and is exactly the case
     * that makes the account stop identifying a tenancy on its own. The order is part of the answer:
     * a warning names the tenancy that was there first, and a warning whose wording depends on row
     * order is one nobody can reproduce.
     */
    List<UUID> tenanciesPaidFrom(UUID workspaceId, String iban);

    /**
     * Remembers the association, or refreshes it if it is already known. Every association is kept
     * rather than the newest replacing the last, because an account paying for two tenancies is a
     * fact about both.
     */
    void learn(UUID workspaceId, String iban, UUID tenancyId, UUID learnedFrom, LocalDate learnedOn);
}
