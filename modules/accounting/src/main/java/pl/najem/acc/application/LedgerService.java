package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LedgerService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public LedgerService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID postRentCharge(UUID tenancyId, BigDecimal amount, LocalDate dueDate, String paymentReference) {
        UUID chargeId = UUID.randomUUID();
        var stream = store.load(tenancyId);
        store.append(tenancyId, "TenancyLedger", stream.version(),
            List.of(new ChargePosted(chargeId, tenancyId, "rent", amount, dueDate)), List.of());
        jdbc.update(
            "insert into acc_charge(charge_id, tenancy_id, component, amount, due_date, payment_reference) values (?,?,?,?,?,?)",
            chargeId, tenancyId, "rent", amount, dueDate, paymentReference);
        jdbc.update("""
            insert into acc_tenancy_status(tenancy_id, status) values (?, 'awaiting')
            on conflict (tenancy_id) do update set status = 'awaiting'
            """, tenancyId);
        return chargeId;
    }
}
