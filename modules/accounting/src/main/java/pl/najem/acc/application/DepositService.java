package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.DepositCharged;
import pl.najem.acc.domain.DepositSettled;
import pl.najem.acc.domain.DepositValorization;
import pl.najem.acc.domain.LegalForm;
import pl.najem.acc.domain.WarningKind;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The deposit, charged once at activation against a multiple snapshotted then.
 *
 * <p>The multiple is of the <em>czynsz</em>, not of the whole monthly figure: adminFee and
 * mediaAdvance are not rent, and valorization at return works from the same base. Under the collapse
 * rule a contract with no split has a rent equal to its total, which is the intended consequence.
 *
 * <p>Caps warn and never refuse. A manager exceeding one is doing something the ledger should record
 * and flag, not block — and a deposit the system refused to record is a deposit nobody can return.
 */
@Service
@Transactional
public class DepositService {

    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final WarningService warnings;

    public DepositService(EventStore store, JdbcTemplate jdbc, WarningService warnings) {
        this.store = store;
        this.jdbc = jdbc;
        this.warnings = warnings;
    }

    /**
     * Charges the agreed deposit, if there is one.
     *
     * <p>A null or non-positive amount means the contract has no deposit — a term, not a deposit of
     * nothing. No charge is posted at all, because a zero-value charge would tell every later reader
     * that a deposit exists and has been settled.
     *
     * @param rentAtCharge the czynsz in force at activation, the base the multiple is taken from
     * @return the deposit's id, or empty when the contract carries none
     */
    public Optional<UUID> chargeOnActivation(UUID workspaceId, UUID tenancyId, BigDecimal amount,
                                             BigDecimal rentAtCharge, String legalForm,
                                             LocalDate dueDate, String paymentReference) {
        if (amount == null || amount.signum() <= 0) {
            return Optional.empty();
        }
        var raised = new ArrayList<Warning>();
        // The cap is a multiple of the czynsz, so with no czynsz there is no multiple to compare and
        // the check cannot run. Saying so is the whole point: a multiplier of zero is not greater
        // than any cap, so passing one to the comparison below reports a check that never happened,
        // and a contract putting its whole monthly into adminFee and mediaAdvance is exactly how a
        // deposit would be placed beyond the cap's reach.
        boolean checkable = rentAtCharge != null && rentAtCharge.signum() > 0;
        if (!checkable) {
            raised.add(Warning.of(WarningKind.DEPOSIT_CAP_UNCHECKABLE,
                "kaucja " + amount + " zł; czynsz wynosi 0, więc nie sprawdzono ustawowego limitu"));
        }
        BigDecimal multiplier = multiplierOf(amount, rentAtCharge);
        LegalForm.of(legalForm).ifPresentOrElse(
            form -> {
                if (checkable && multiplier.compareTo(BigDecimal.valueOf(form.depositCapInMonths())) > 0) {
                    raised.add(Warning.of(WarningKind.DEPOSIT_CAP_EXCEEDED,
                        "kaucja " + amount + " zł to " + multiplier + "-krotność czynszu "
                            + rentAtCharge + " zł; ustawowy limit dla formy " + form.name()
                            + " to " + form.depositCapInMonths() + "-krotność"));
                }
            },
            () -> raised.add(Warning.of(WarningKind.UNKNOWN_LEGAL_FORM,
                "nieznana forma najmu \"" + legalForm + "\"; nie sprawdzono limitu kaucji")));

        UUID chargeId = postDepositCharge(workspaceId, tenancyId, amount, dueDate, paymentReference);
        UUID depositId = UUID.randomUUID();
        jdbc.update("""
            insert into acc_deposit(deposit_id, workspace_id, tenancy_id, charge_id, legal_form,
                                    nominal_amount, rent_at_charge, multiplier, state, charged_on)
            values (?,?,?,?,?,?,?,?, 'charged', ?)
            """, depositId, workspaceId, tenancyId, chargeId, legalForm, amount, rentAtCharge,
            multiplier, dueDate);
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(),
            List.of(new DepositCharged(depositId, tenancyId, chargeId, amount, multiplier,
                rentAtCharge, legalForm)), List.of());
        warnings.raise(workspaceId, tenancyId, raised);
        return Optional.of(depositId);
    }

    /**
     * Gives the deposit back at the end of the tenancy.
     *
     * <p>Art. 6 ust. 4: it is returned in the amount corresponding to the agreed multiple of the
     * czynsz in force on the day of return, less what the landlord is lawfully owed, and never less
     * than the sum actually taken. The multiple is the one snapshotted at activation — recomputing
     * it from today's figures would re-price the contract every time the rent moved.
     *
     * <p>Deductions are itemised against the charges they settle rather than recorded as one total,
     * because a disputed deduction has to be traceable to the obligation it paid. Arrears beyond the
     * deposit are not forgiven by settling it; what the deposit could not cover stays owed.
     *
     * @param rentAtReturn the czynsz in force on the day of return
     * @return what actually went back to the tenant
     */
    public BigDecimal settle(UUID workspaceId, UUID tenancyId, BigDecimal rentAtReturn,
                             LocalDate returnedOn) {
        var deposit = held(workspaceId, tenancyId);
        UUID depositId = (UUID) deposit.get("deposit_id");
        var valorization = DepositValorization.compute((BigDecimal) deposit.get("multiplier"),
            (BigDecimal) deposit.get("nominal_amount"), rentAtReturn);

        BigDecimal remaining = valorization.valorized();
        BigDecimal deducted = BigDecimal.ZERO;
        for (var charge : arrears(workspaceId, tenancyId)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal owed = (BigDecimal) charge.get("owed");
            BigDecimal taken = remaining.min(owed);
            jdbc.update("""
                insert into acc_deposit_deduction(deduction_id, workspace_id, deposit_id, charge_id,
                                                  amount, deducted_on)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), workspaceId, depositId, charge.get("charge_id"), taken,
                returnedOn);
            jdbc.update("""
                update acc_charge
                set allocated_amount = allocated_amount + ?, allocated = allocated_amount + ? >= amount
                where workspace_id = ? and charge_id = ?
                """, taken, taken, workspaceId, charge.get("charge_id"));
            remaining = remaining.subtract(taken);
            deducted = deducted.add(taken);
        }

        jdbc.update("""
            update acc_deposit
            set state = 'settled', settled_on = ?, rent_at_return = ?, valorized_amount = ?,
                deducted_amount = ?, returned_amount = ?
            where workspace_id = ? and deposit_id = ?
            """, returnedOn, rentAtReturn, valorization.valorized(), deducted, remaining,
            workspaceId, depositId);
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(),
            List.of(new DepositSettled(depositId, tenancyId, valorization.valorized(), deducted,
                remaining, rentAtReturn, valorization.floorApplied())), List.of());
        return remaining;
    }

    /**
     * The deposit this workspace is actually holding for the tenancy.
     *
     * <p>Scoped by workspace and required to have been paid. A charge nobody settled is not money in
     * hand, and valorizing it would invent funds — so the unpaid case refuses rather than returning
     * a figure that looks like a settlement.
     *
     * <p>Taking the single row is safe because {@code acc_deposit} is unique on
     * {@code (workspace_id, tenancy_id)} — so a redelivered activation, which the at-least-once
     * outbox is entitled to produce, is refused by the database rather than producing a second
     * deposit with a different amount and a different answer to what the tenant gets back. That
     * constraint is the reason this query needs no tie-break, and it is asserted by name in
     * {@code AccountingSchemaShapeTest} so that dropping it fails a test rather than silently making
     * a refund depend on row order.
     */
    private Map<String, Object> held(UUID workspaceId, UUID tenancyId) {
        var rows = jdbc.queryForList("""
            select d.deposit_id, d.multiplier, d.nominal_amount, d.state,
                   c.amount - c.allocated_amount as unpaid
            from acc_deposit d join acc_charge c on c.charge_id = d.charge_id
            where d.workspace_id = ? and d.tenancy_id = ?
            """, workspaceId, tenancyId);
        if (rows.isEmpty()) {
            throw new DepositNotHeldException(tenancyId, "none was charged in this workspace");
        }
        var deposit = rows.getFirst();
        if ("settled".equals(deposit.get("state"))) {
            throw new IllegalStateException("the deposit for tenancy " + tenancyId
                + " has already been returned; returning it again would pay the tenant twice");
        }
        if (((BigDecimal) deposit.get("unpaid")).signum() > 0) {
            throw new DepositNotHeldException(tenancyId,
                "it was charged but never paid, so there is nothing to give back");
        }
        return deposit;
    }

    /** What the tenancy still owes, oldest first. The deposit charge is not one of its own arrears. */
    private List<Map<String, Object>> arrears(UUID workspaceId, UUID tenancyId) {
        return jdbc.queryForList("""
            select charge_id, amount - allocated_amount as owed from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
              and component <> ?
            order by due_date, charge_id
            """, workspaceId, tenancyId, Component.DEPOSIT.wireName());
    }

    private UUID postDepositCharge(UUID workspaceId, UUID tenancyId, BigDecimal amount,
                                   LocalDate dueDate, String paymentReference) {
        UUID chargeId = UUID.randomUUID();
        jdbc.update("""
            insert into acc_charge(charge_id, workspace_id, tenancy_id, component, amount, due_date,
                                   payment_reference)
            values (?,?,?,?,?,?,?)
            """, chargeId, workspaceId, tenancyId, Component.DEPOSIT.wireName(), amount, dueDate,
            paymentReference);
        return chargeId;
    }

    /**
     * How many months' rent the deposit is. Kept to two places because it is a snapshot of an agreed
     * figure rather than a computed one — a contract says "two months", and 2.00 should read back as
     * the term it was.
     */
    private static BigDecimal multiplierOf(BigDecimal amount, BigDecimal rentAtCharge) {
        if (rentAtCharge == null || rentAtCharge.signum() <= 0) {
            // Null, not zero. There is no multiple of nothing, and a stored zero is indistinguishable
            // from a computed one to every later reader -- including valorization at return, which
            // works from this same base and would compute a valorized deposit of nothing.
            return null;
        }
        return amount.divide(rentAtCharge, 2, RoundingMode.HALF_UP);
    }
}
