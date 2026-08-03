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

    public void confirm(UUID paymentId) {
        UUID chargeId = jdbc.queryForObject(
            "select charge_id from acc_suggestion where payment_id = ?", UUID.class, paymentId);
        BigDecimal amount = jdbc.queryForObject(
            "select amount from acc_payment where payment_id = ?", BigDecimal.class, paymentId);
        UUID tenancyId = jdbc.queryForObject(
            "select tenancy_id from acc_charge where charge_id = ?", UUID.class, chargeId);

        var stream = store.load(paymentId);
        store.append(paymentId, "Payment", stream.version(),
            List.of(new PaymentAllocated(paymentId, chargeId, amount)), List.of());
        jdbc.update("update acc_charge set allocated = true where charge_id = ?", chargeId);
        jdbc.update("update acc_payment set status = 'allocated' where payment_id = ?", paymentId);
        jdbc.update("update acc_tenancy_status set status = 'green' where tenancy_id = ?", tenancyId);
    }
}
