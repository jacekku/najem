package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.PaymentIngested;
import pl.najem.eventstore.EventStore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class IngestionService {

    private final BankStatementPort bank;
    private final EventStore store;
    private final JdbcTemplate jdbc;

    public IngestionService(BankStatementPort bank, EventStore store, JdbcTemplate jdbc) {
        this.bank = bank;
        this.store = store;
        this.jdbc = jdbc;
    }

    public void fetchAndIngest() {
        for (BankLine line : bank.fetchSince(LocalDate.now().minusDays(30))) {
            ingest(line);
        }
    }

    public void ingest(BankLine line) {
        Integer existing = jdbc.queryForObject(
            "select count(*) from acc_payment where external_id = ?", Integer.class, line.externalId());
        if (existing != null && existing > 0) {
            return;
        }
        UUID paymentId = UUID.randomUUID();
        store.append(paymentId, "Payment", 0,
            List.of(new PaymentIngested(paymentId, line.externalId(), line.amount(),
                line.title(), line.bookingDate())), List.of());
        jdbc.update(
            "insert into acc_payment(payment_id, external_id, amount, title, booking_date, status) values (?,?,?,?,?,'unmatched')",
            paymentId, line.externalId(), line.amount(), line.title(), line.bookingDate());
        suggestExactMatch(paymentId, line);
    }

    private void suggestExactMatch(UUID paymentId, BankLine line) {
        var chargeIds = jdbc.queryForList("""
            select charge_id from acc_charge
            where payment_reference = ? and amount = ? and not allocated
            order by due_date limit 1
            """, UUID.class, line.title(), line.amount());
        if (!chargeIds.isEmpty()) {
            jdbc.update("insert into acc_suggestion(payment_id, charge_id) values (?,?)",
                paymentId, chargeIds.getFirst());
            jdbc.update("update acc_payment set status = 'suggested' where payment_id = ?", paymentId);
        }
    }
}
