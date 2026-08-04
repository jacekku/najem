package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

    public UUID postRentCharge(UUID workspaceId, UUID tenancyId, BigDecimal amount, LocalDate dueDate,
                               String paymentReference) {
        return postMonthlyCharges(workspaceId, tenancyId, MonthlyBreakdown.unsplit(amount), dueDate,
            paymentReference).chargeIds().getFirst();
    }

    /**
     * Charges one month against a tenancy, one line per contractual component. The tenant is quoted
     * a single total; the ledger keeps the split the contract carries, or collapses it into rent
     * when the contract carries none.
     */
    public PostedCharges postMonthlyCharges(UUID workspaceId, UUID tenancyId, MonthlyBreakdown breakdown,
                                            LocalDate dueDate, String paymentReference) {
        var lines = breakdown.chargeLines();
        var chargeIds = new ArrayList<UUID>(lines.size());
        var events = new ArrayList<Object>(lines.size());
        for (ChargeLine line : lines) {
            UUID chargeId = UUID.randomUUID();
            chargeIds.add(chargeId);
            events.add(new ChargePosted(chargeId, tenancyId, line.component().wireName(),
                line.amount(), dueDate));
        }

        var stream = store.load(tenancyId);
        store.append(tenancyId, "TenancyLedger", stream.version(), List.copyOf(events), List.of());
        for (int i = 0; i < lines.size(); i++) {
            jdbc.update("""
                insert into acc_charge(charge_id, workspace_id, tenancy_id, component, amount, due_date, payment_reference)
                values (?,?,?,?,?,?,?)
                """, chargeIds.get(i), workspaceId, tenancyId, lines.get(i).component().wireName(),
                lines.get(i).amount(), dueDate, paymentReference);
        }
        jdbc.update("""
            insert into acc_tenancy_status(tenancy_id, workspace_id, status) values (?, ?, 'awaiting')
            on conflict (tenancy_id) do update set status = 'awaiting'
            """, tenancyId, workspaceId);
        return new PostedCharges(List.copyOf(chargeIds), breakdown.warnings());
    }

    /**
     * The czynsz in force on a date — the base for deposit valorization, which excludes adminFee and
     * mediaAdvance (DEPOSIT §1). Under the collapse rule this is the whole monthly total, which is
     * exactly the intended consequence of a contract without a split.
     */
    public BigDecimal rentComponentAsOf(UUID workspaceId, UUID tenancyId, LocalDate asOf) {
        BigDecimal rent = jdbc.queryForObject("""
            select amount from acc_charge
            where workspace_id = ? and tenancy_id = ? and component = 'rent' and due_date <= ?
            order by due_date desc limit 1
            """, BigDecimal.class, workspaceId, tenancyId, asOf);
        return rent == null ? BigDecimal.ZERO : rent;
    }
}
