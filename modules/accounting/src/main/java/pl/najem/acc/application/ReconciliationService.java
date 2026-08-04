package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.acc.domain.WarningKind;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReconciliationService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final WarningService warnings;

    @Autowired
    public ReconciliationService(EventStore store, JdbcTemplate jdbc, WarningService warnings) {
        this.store = store;
        this.jdbc = jdbc;
        this.warnings = warnings;
    }

    /** Reconciliation with its own warning register, for tests and callers outside the context. */
    public ReconciliationService(EventStore store, JdbcTemplate jdbc) {
        this(store, jdbc, new WarningService(jdbc));
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
        BigDecimal amount = jdbc.queryForObject(
            "select amount from acc_payment where workspace_id = ? and payment_id = ?",
            BigDecimal.class, workspaceId, paymentId);
        UUID tenancyId = jdbc.queryForObject(
            "select tenancy_id from acc_charge where workspace_id = ? and charge_id = ?",
            UUID.class, workspaceId, chargeId);

        var stream = store.load(paymentId, "Payment");
        store.append(paymentId, "Payment", stream.version(),
            List.of(new PaymentAllocated(paymentId, chargeId, amount)), List.of());
        jdbc.update("update acc_charge set allocated = true where charge_id = ?", chargeId);
        jdbc.update("update acc_payment set status = 'allocated' where payment_id = ?", paymentId);
        jdbc.update("update acc_tenancy_status set status = 'green' where tenancy_id = ?", tenancyId);
        rememberPayerAccount(workspaceId, paymentId, tenancyId);
    }

    /**
     * A confirmed match is the only trustworthy statement that this account pays for this tenancy —
     * a human looked at it. That is what tier 3 matches on next month, so it is learned here rather
     * than at ingestion, where the ledger was only guessing.
     *
     * <p>An account that starts paying for a different tenancy is taken at its newer word, because
     * the manager just confirmed it — but the manager is told, since a silent reassignment would
     * suggest the wrong tenancy every month afterwards and look like the ledger's own opinion.
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
            select tenancy_id from acc_payer_account where workspace_id = ? and counterparty_iban = ?
            """, UUID.class, workspaceId, iban);
        if (!known.isEmpty() && !known.getFirst().equals(tenancyId)) {
            warnings.raise(workspaceId, tenancyId, List.of(Warning.of(WarningKind.PAYER_ACCOUNT_REASSIGNED,
                "konto " + iban + " płaciło dotąd za najem " + known.getFirst()
                    + "; od teraz podpowiadamy najem " + tenancyId)));
        }
        jdbc.update("""
            insert into acc_payer_account(workspace_id, counterparty_iban, tenancy_id, learned_from, learned_on)
            values (?,?,?,?,?)
            on conflict (workspace_id, counterparty_iban)
            do update set tenancy_id = excluded.tenancy_id,
                          learned_from = excluded.learned_from,
                          learned_on = excluded.learned_on
            """, workspaceId, iban, tenancyId, paymentId, LocalDate.now());
    }
}
