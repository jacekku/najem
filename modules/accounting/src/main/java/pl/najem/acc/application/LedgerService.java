package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ChargeDeactivated;
import pl.najem.acc.domain.ChargePosted;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.CreditNoteIssued;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class LedgerService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final WarningService warnings;
    private final ArrearsBoardService board;

    @Autowired
    public LedgerService(EventStore store, JdbcTemplate jdbc, WarningService warnings,
                         ArrearsBoardService board) {
        this.store = store;
        this.jdbc = jdbc;
        this.warnings = warnings;
        this.board = board;
    }

    public UUID postRentCharge(UUID workspaceId, UUID tenancyId, BigDecimal amount, LocalDate dueDate,
                               String paymentReference) {
        return postMonthlyCharges(workspaceId, tenancyId, MonthlyBreakdown.unsplit(amount), dueDate,
            paymentReference).chargeIds().getFirst();
    }

    /**
     * Charges a single component against a tenancy — a deposit, a repair recharge, interest. The
     * monthly cycle goes through {@link #postMonthlyCharges}; this is for the obligations that
     * arrive on their own.
     */
    public UUID postCharge(UUID workspaceId, UUID tenancyId, Component component, BigDecimal amount,
                           LocalDate dueDate, String paymentReference) {
        UUID chargeId = UUID.randomUUID();
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(),
            List.of(new ChargePosted(chargeId, tenancyId, component.wireName(), amount, dueDate)),
            List.of());
        jdbc.update("""
            insert into acc_charge(charge_id, workspace_id, tenancy_id, component, amount, due_date, payment_reference)
            values (?,?,?,?,?,?,?)
            """, chargeId, workspaceId, tenancyId, component.wireName(), amount, dueDate, paymentReference);
        board.refresh(workspaceId, tenancyId);
        return chargeId;
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

        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(), List.copyOf(events), List.of());
        for (int i = 0; i < lines.size(); i++) {
            jdbc.update("""
                insert into acc_charge(charge_id, workspace_id, tenancy_id, component, amount, due_date, payment_reference)
                values (?,?,?,?,?,?,?)
                """, chargeIds.get(i), workspaceId, tenancyId, lines.get(i).component().wireName(),
                lines.get(i).amount(), dueDate, paymentReference);
        }
        board.refresh(workspaceId, tenancyId);
        var raised = breakdown.warnings();
        warnings.raise(workspaceId, tenancyId, raised);
        return new PostedCharges(List.copyOf(chargeIds), raised);
    }

    /**
     * Withdraws an unpaid charge. The row survives as inactive with a reversal underneath — the
     * ledger does not delete facts it has asserted.
     */
    public void deactivateCharge(UUID workspaceId, UUID chargeId, String reason) {
        var charge = chargeIn(workspaceId, chargeId);
        if ((Boolean) charge.get("allocated")) {
            throw new ChargeAlreadyPaidException(
                "charge " + chargeId + " is paid and cannot be deactivated; issue a credit note instead");
        }
        UUID tenancyId = (UUID) charge.get("tenancy_id");
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(),
            List.of(new ChargeDeactivated(chargeId, tenancyId, reason)), List.of());
        jdbc.update("""
            update acc_charge set active = false where workspace_id = ? and charge_id = ?
            """, workspaceId, chargeId);
        // A withdrawn charge is not an obligation, so it must stop colouring the board. Without this
        // a tenancy whose only arrear was billed in error stays red until something else moves.
        board.refresh(workspaceId, tenancyId);
    }

    /**
     * Corrects an already-paid charge. The charge stands and a credit note is issued against it —
     * the tenant is entitled to the document, and the pair is the audit trail.
     */
    public UUID issueCreditNote(UUID workspaceId, UUID chargeId, BigDecimal amount, String reason) {
        var charge = chargeIn(workspaceId, chargeId);
        if (!(Boolean) charge.get("allocated")) {
            throw new ChargeNotPaidException(
                "charge " + chargeId + " is unpaid; deactivate it instead of issuing a credit note");
        }
        var charged = (BigDecimal) charge.get("amount");
        if (amount.signum() <= 0 || amount.compareTo(charged) > 0) {
            throw new IllegalArgumentException(
                "credit note of " + amount + " does not fit the charge of " + charged);
        }
        UUID tenancyId = (UUID) charge.get("tenancy_id");
        UUID creditNoteId = UUID.randomUUID();
        LocalDate issuedOn = (LocalDate) charge.get("due_date");
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(),
            List.of(new CreditNoteIssued(creditNoteId, chargeId, tenancyId, amount, reason, issuedOn)),
            List.of());
        jdbc.update("""
            insert into acc_credit_note(credit_note_id, workspace_id, charge_id, tenancy_id, amount, reason, issued_on)
            values (?,?,?,?,?,?,?)
            """, creditNoteId, workspaceId, chargeId, tenancyId, amount, reason, issuedOn);
        return creditNoteId;
    }

    /** A charge outside the caller's workspace does not exist, rather than being forbidden. */
    private Map<String, Object> chargeIn(UUID workspaceId, UUID chargeId) {
        var rows = jdbc.queryForList("""
            select tenancy_id, amount, due_date, allocated from acc_charge
            where workspace_id = ? and charge_id = ?
            """, workspaceId, chargeId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no charge " + chargeId + " in workspace " + workspaceId);
        }
        var row = rows.getFirst();
        return Map.of("tenancy_id", row.get("tenancy_id"),
            "amount", row.get("amount"),
            "due_date", ((java.sql.Date) row.get("due_date")).toLocalDate(),
            "allocated", row.get("allocated"));
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
