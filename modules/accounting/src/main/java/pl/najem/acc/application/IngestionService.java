package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.MatchTier;
import pl.najem.acc.domain.PaymentIngested;
import pl.najem.eventstore.EventStore;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Taking a bank statement in, and deciding what — if anything — each line looks like it is paying.
 *
 * <p>The ladder's rungs are lookups and live behind {@link InvoiceMatching}. The <em>order</em> they
 * are tried in, and where it stops, is the decision this service exists to make and stays here: a
 * store that answered rungs in its own order would be deciding how confident the module is.
 */
@Service
@Transactional
public class IngestionService {

    private final BankStatementPort bank;
    private final EventStore store;
    private final PaymentRepository payments;
    private final InvoiceMatching matching;
    private final PayerAccountRepository payerAccounts;
    private final SuggestionRepository suggestions;
    private final WorkspaceAccountService accounts;
    private final MatchingPolicy policy;
    private final Clock clock;

    @Autowired
    public IngestionService(BankStatementPort bank, EventStore store, PaymentRepository payments,
                            InvoiceMatching matching, PayerAccountRepository payerAccounts,
                            SuggestionRepository suggestions, WorkspaceAccountService accounts,
                            @Value("${acc.matching.tiers-enabled:false}") boolean tiersEnabled,
                            @Value("${acc.matching.auto-confirm:false}") boolean autoConfirm) {
        this(bank, store, payments, matching, payerAccounts, suggestions, accounts,
            new MatchingPolicy(tiersEnabled, autoConfirm), Clock.systemDefaultZone());
    }

    public IngestionService(BankStatementPort bank, EventStore store, PaymentRepository payments,
                            InvoiceMatching matching, PayerAccountRepository payerAccounts,
                            SuggestionRepository suggestions, WorkspaceAccountService accounts,
                            MatchingPolicy policy, Clock clock) {
        this.bank = bank;
        this.store = store;
        this.payments = payments;
        this.matching = matching;
        this.payerAccounts = payerAccounts;
        this.suggestions = suggestions;
        this.accounts = accounts;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Ingests the workspace's own statement, and only that one.
     *
     * <p>The account is looked up per workspace and there is no fallback: a workspace nobody has
     * registered an account for cannot ingest. It used to fetch from the configured account and hand
     * every line to whichever workspace called, which meant one transfer could be suggested against
     * a charge in two different agencies and, if both accepted, read as paid in both.
     */
    public void fetchAndIngest(UUID workspaceId) {
        var iban = accounts.accountOf(workspaceId);
        for (BankLine line : bank.fetchSince(LocalDate.now(clock).minusDays(30), iban)) {
            ingest(workspaceId, line);
        }
    }

    public void ingest(UUID workspaceId, BankLine line) {
        if (payments.alreadyIngested(workspaceId, line.externalId())) {
            return;
        }
        UUID paymentId = UUID.randomUUID();
        store.append(paymentId, "Payment", 0,
            List.of(new PaymentIngested(paymentId, line.externalId(), line.amount(),
                line.title(), line.bookingDate())), List.of());
        payments.record(workspaceId, paymentId, line);
        climbTheLadder(workspaceId, paymentId, line);
    }

    /**
     * The ladder, rung by rung, stopping at the first that answers. A line only reaches it if it is
     * money coming in, in the currency this ledger holds — an outgoing debit or a euro transfer is
     * recorded as the bank fact it is and left for a human, however exactly its reference and amount
     * line up.
     *
     * <p>Tiers 2-4 are built and off. The rung that answers is recorded with the suggestion so the
     * manager can see whether the ledger is certain or merely guessing.
     */
    private void climbTheLadder(UUID workspaceId, UUID paymentId, BankLine line) {
        if (!line.isCredit() || !line.isZloty()) {
            return;
        }
        if (suggest(workspaceId, paymentId, exactMatch(workspaceId, line), MatchTier.EXACT)) {
            return;
        }
        if (!policy.tiersEnabled()) {
            return;
        }
        if (suggest(workspaceId, paymentId, referenceMatch(workspaceId, line), MatchTier.REFERENCE)) {
            return;
        }
        suggest(workspaceId, paymentId, rememberedPayerMatch(workspaceId, line),
            MatchTier.REMEMBERED_PAYER);
        // Nothing left to try: tier 4 is the manual queue, which is the 'unmatched' the row already has.
    }

    /** Tier 1 — the reference and the amount both name an open charge. */
    private Optional<UUID> exactMatch(UUID workspaceId, BankLine line) {
        return matching.byExactReferenceAndAmount(workspaceId, line.title(), line.amount());
    }

    /**
     * Tier 2 — the reference survives being typed carelessly. Case and separators are discarded on
     * both sides, and the charge's reference need only appear somewhere in the title, because banks
     * prepend their own words and payers paste more than they were asked to.
     *
     * <p>A title that normalises to nothing is not asked about at all: it would be a substring of
     * every reference, and a rung that matches everything is worse than one that matches nothing.
     */
    private Optional<UUID> referenceMatch(UUID workspaceId, BankLine line) {
        String title = normalise(line.title());
        return title.isEmpty() ? Optional.empty() : matching.byReferenceWithin(workspaceId, title);
    }

    /**
     * Tier 3 — no usable reference, but this account has paid for a tenancy before and a manager
     * confirmed it. Suggests that tenancy's oldest open charge.
     *
     * <p>A free-text MT940 {@code :86:} carries no counterparty at all, which is an ordinary bank
     * sending less: with nothing to look up there is nothing to suggest, and the null must not
     * become a key that matches every other line which also arrived without one.
     *
     * <p>An account that pays for more than one tenancy — a guarantor with two children's flats —
     * cannot identify one from the account alone, so this rung declines and the line goes to a
     * human. Guessing would be wrong half the time and would look like the ledger's own opinion.
     */
    private Optional<UUID> rememberedPayerMatch(UUID workspaceId, BankLine line) {
        if (line.counterpartyIban() == null || line.counterpartyIban().isBlank()) {
            return Optional.empty();
        }
        var tenancies = payerAccounts.tenanciesPaidFrom(workspaceId, line.counterpartyIban());
        if (tenancies.size() != 1) {
            return Optional.empty();
        }
        return matching.oldestOpenOf(workspaceId, tenancies.getFirst());
    }

    /** Records the rung that answered. Nothing here allocates — the manager still confirms. */
    private boolean suggest(UUID workspaceId, UUID paymentId, Optional<UUID> invoiceId,
                            MatchTier tier) {
        if (invoiceId.isEmpty()) {
            return false;
        }
        suggestions.suggest(workspaceId, paymentId, invoiceId.get(), tier);
        payments.markSuggested(workspaceId, paymentId);
        return true;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
