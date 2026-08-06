package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.WarningKind;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReconciliationService {

    private final SuggestionRepository suggestions;
    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PayerAccountRepository payerAccounts;
    private final WarningService warnings;
    private final AccountingService accounting;
    private final Clock clock;

    @Autowired
    public ReconciliationService(SuggestionRepository suggestions, InvoiceRepository invoices,
                                 PaymentRepository payments, PayerAccountRepository payerAccounts,
                                 WarningService warnings, AccountingService accounting,
                                 Clock clock) {
        this.suggestions = suggestions;
        this.invoices = invoices;
        this.payments = payments;
        this.payerAccounts = payerAccounts;
        this.warnings = warnings;
        this.accounting = accounting;
        this.clock = clock;
    }

    /**
     * Confirms a suggested match. A payment belonging to another workspace is invisible rather than
     * forbidden — the workspace is part of the question, so there is nothing to confirm and nothing
     * happens.
     */
    public void confirm(UUID workspaceId, UUID paymentId) {
        var suggested = suggestions.suggestedInvoice(workspaceId, paymentId);
        if (suggested.isEmpty()) {
            return;
        }
        UUID invoiceId = suggested.get();
        UUID tenancyId = invoices.find(workspaceId, invoiceId)
            .orElseThrow(() -> new IllegalStateException("suggestion for payment " + paymentId
                + " names charge " + invoiceId + ", which is not in workspace " + workspaceId))
            .tenancyId();

        // What the manager confirms is which tenancy the money belongs to. Where it comes to rest
        // within that tenancy is the ledger's rule, not theirs: oldest due first, rent last.
        accounting.allocate(workspaceId, paymentId, tenancyId);
        rememberPayerAccount(workspaceId, paymentId, tenancyId);
    }

    /**
     * A confirmed match is the only trustworthy statement that this account pays for this tenancy —
     * a human looked at it. That is what tier 3 matches on next month, so it is learned here rather
     * than at ingestion, where the ledger was only guessing.
     *
     * <p>An account may pay for several tenancies — a parent guaranteeing two children's flats is
     * ordinary — so every association is kept rather than the newest overwriting the last. When an
     * account first becomes ambiguous the manager is told once, because that is the moment tier 3
     * stops being able to identify a tenancy from it. A first association is how tier 3 learns
     * anything and is not news.
     *
     * <p>One question to the register instead of three to the database. "Which tenancies does this
     * account pay for" answers both halves — whether any others are known, and whether this one
     * already is — and the two used to be separate queries that could in principle disagree.
     */
    private void rememberPayerAccount(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        var payerAccount = payments.payerAccountOf(workspaceId, paymentId);
        if (payerAccount.isEmpty()) {
            return;
        }
        String iban = payerAccount.get();
        var known = payerAccounts.tenanciesPaidFrom(workspaceId, iban);
        var others = known.stream().filter(other -> !other.equals(tenancyId)).toList();
        if (!others.isEmpty() && !known.contains(tenancyId)) {
            warnings.raise(workspaceId, tenancyId,
                List.of(new WarningToRaise(WarningKind.PAYER_ACCOUNT_AMBIGUOUS,
                    "konto " + iban + " płaci za więcej niż jeden najem (" + others.getFirst() + ", "
                        + tenancyId + "); nie podpowiadamy już najmu na podstawie samego konta")));
        }
        payerAccounts.learn(workspaceId, iban, tenancyId, paymentId, LocalDate.now(clock));
    }
}
