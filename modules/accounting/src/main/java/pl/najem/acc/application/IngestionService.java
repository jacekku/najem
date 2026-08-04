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

    public void fetchAndIngest(UUID workspaceId) {
        for (BankLine line : bank.fetchSince(LocalDate.now().minusDays(30))) {
            ingest(workspaceId, line);
        }
    }

    public void ingest(UUID workspaceId, BankLine line) {
        Integer existing = jdbc.queryForObject(
            "select count(*) from acc_payment where workspace_id = ? and external_id = ?",
            Integer.class, workspaceId, line.externalId());
        if (existing != null && existing > 0) {
            return;
        }
        UUID paymentId = UUID.randomUUID();
        store.append(paymentId, "Payment", 0,
            List.of(new PaymentIngested(paymentId, line.externalId(), line.amount(),
                line.title(), line.bookingDate())), List.of());
        jdbc.update("""
            insert into acc_payment(payment_id, workspace_id, external_id, amount, title, booking_date,
                                    status, counterparty_name, counterparty_iban, bank_reference,
                                    value_date, direction, currency)
            values (?,?,?,?,?,?,'unmatched',?,?,?,?,?,?)
            """, paymentId, workspaceId, line.externalId(), line.amount(), line.title(), line.bookingDate(),
            line.counterpartyName(), line.counterpartyIban(), line.bankReference(), line.valueDate(),
            line.creditDebitIndicator(), line.currency());
        suggestExactMatch(workspaceId, paymentId, line);
    }

    /**
     * A line only reaches the ladder if it is money coming in, in the currency this ledger holds.
     * An outgoing debit or a euro transfer is recorded as the bank fact it is and left for a human —
     * it must never settle a charge, however exactly its reference and amount line up.
     */
    private void suggestExactMatch(UUID workspaceId, UUID paymentId, BankLine line) {
        if (!line.isCredit() || !line.isZloty()) {
            return;
        }
        var chargeIds = jdbc.queryForList("""
            select charge_id from acc_charge
            where workspace_id = ? and payment_reference = ? and amount = ? and not allocated and active
            order by due_date limit 1
            """, UUID.class, workspaceId, line.title(), line.amount());
        if (!chargeIds.isEmpty()) {
            jdbc.update("insert into acc_suggestion(payment_id, workspace_id, charge_id) values (?,?,?)",
                paymentId, workspaceId, chargeIds.getFirst());
            jdbc.update("update acc_payment set status = 'suggested' where payment_id = ?", paymentId);
        }
    }
}
