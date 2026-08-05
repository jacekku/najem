package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final WarningService warnings;
    private final AccountingService accounting;
    private final Clock clock;

    @Autowired
    public ReconciliationService(JdbcTemplate jdbc, WarningService warnings,
                                 AccountingService accounting, Clock clock) {
        this.jdbc = jdbc;
        this.warnings = warnings;
        this.accounting = accounting;
        this.clock = clock;
    }

    /** Reconciliation with its own collaborators, for tests and callers outside the context. */
    public ReconciliationService(JdbcTemplate jdbc, AccountingService accounting) {
        this(jdbc, new WarningService(jdbc), accounting, Clock.systemDefaultZone());
    }

    /**
     * Confirms a suggested match. A payment belonging to another workspace is invisible rather than
     * forbidden — the query scopes the boundary, so there is nothing to confirm and nothing happens.
     */
    public void confirm(UUID workspaceId, UUID paymentId) {
        var suggested = jdbc.queryForList(
            "select charge_id from acc_suggestion where workspace_id = ? and payment_id = ?",
            UUID.class, workspaceId, paymentId);
        if (suggested.isEmpty()) {
            return;
        }
        UUID chargeId = suggested.getFirst();
        UUID tenancyId = jdbc.queryForObject(
            "select tenancy_id from acc_charge where workspace_id = ? and charge_id = ?",
            UUID.class, workspaceId, chargeId);

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
     */
    private void rememberPayerAccount(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        var payerAccounts = jdbc.queryForList("""
            select counterparty_iban from acc_payment
            where workspace_id = ? and payment_id = ? and counterparty_iban is not null
            """, String.class, workspaceId, paymentId);
        if (payerAccounts.isEmpty()) {
            return;
        }
        String iban = payerAccounts.getFirst();
        var known = jdbc.queryForList("""
            select tenancy_id from acc_payer_account
            where workspace_id = ? and counterparty_iban = ? and tenancy_id <> ?
            """, UUID.class, workspaceId, iban, tenancyId);
        boolean alreadyKnownHere = jdbc.queryForObject("""
            select count(*) from acc_payer_account
            where workspace_id = ? and counterparty_iban = ? and tenancy_id = ?
            """, Integer.class, workspaceId, iban, tenancyId) > 0;
        if (!known.isEmpty() && !alreadyKnownHere) {
            warnings.raise(workspaceId, tenancyId, List.of(Warning.of(WarningKind.PAYER_ACCOUNT_AMBIGUOUS,
                "konto " + iban + " płaci za więcej niż jeden najem (" + known.getFirst() + ", "
                    + tenancyId + "); nie podpowiadamy już najmu na podstawie samego konta")));
        }
        jdbc.update("""
            insert into acc_payer_account(workspace_id, counterparty_iban, tenancy_id, learned_from, learned_on)
            values (?,?,?,?,?)
            on conflict (workspace_id, counterparty_iban, tenancy_id)
            do update set learned_from = excluded.learned_from, learned_on = excluded.learned_on
            """, workspaceId, iban, tenancyId, paymentId, LocalDate.now(clock));
    }
}
