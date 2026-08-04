package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.DepositCharged;
import pl.najem.acc.domain.LegalForm;
import pl.najem.acc.domain.WarningKind;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
        BigDecimal multiplier = multiplierOf(amount, rentAtCharge);
        LegalForm.of(legalForm).ifPresentOrElse(
            form -> {
                if (multiplier.compareTo(BigDecimal.valueOf(form.depositCapInMonths())) > 0) {
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
            return BigDecimal.ZERO;
        }
        return amount.divide(rentAtCharge, 2, RoundingMode.HALF_UP);
    }
}
