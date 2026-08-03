package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.PaymentAllocated;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReconciliationService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public ReconciliationService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
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

        var stream = store.load(paymentId);
        store.append(paymentId, "Payment", stream.version(),
            List.of(new PaymentAllocated(paymentId, chargeId, amount)), List.of());
        jdbc.update("update acc_charge set allocated = true where charge_id = ?", chargeId);
        jdbc.update("update acc_payment set status = 'allocated' where payment_id = ?", paymentId);
        jdbc.update("update acc_tenancy_status set status = 'green' where tenancy_id = ?", tenancyId);
    }
}
