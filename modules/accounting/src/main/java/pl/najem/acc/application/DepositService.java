package pl.najem.acc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.Component;
import pl.najem.acc.domain.DepositAssessment;
import pl.najem.acc.domain.DepositCharged;
import pl.najem.acc.domain.DepositSettled;
import pl.najem.acc.domain.DepositValorization;
import pl.najem.acc.domain.HeldDeposit;
import pl.najem.acc.domain.Invoice;
import pl.najem.acc.domain.WarningKind;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>Caps warn and never refuse. A manager exceeding one is doing something the record should keep
 * and flag, not block — and a deposit the system refused to record is a deposit nobody can return.
 * Whether a cap is exceeded is decided by {@link DepositAssessment}; the sentences below are this
 * layer's, because they are addressed to a person.
 */
@Service
@Transactional
public class DepositService {

    private final EventStore store;
    private final DepositRepository deposits;
    private final InvoiceRepository invoices;
    private final WarningService warnings;

    public DepositService(EventStore store, DepositRepository deposits, InvoiceRepository invoices,
                          WarningService warnings) {
        this.store = store;
        this.deposits = deposits;
        this.invoices = invoices;
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
        var assessment = DepositAssessment.assess(amount, rentAtCharge, legalForm);

        UUID invoiceId = UUID.randomUUID();
        // Through the invoice port rather than InvoiceService: the obligation the tenant pays is an
        // ordinary charge row, but a deposit announces itself with DepositCharged rather than
        // ChargePosted, and posting through the service would append a second event for the same
        // fact and refresh the board on a charge that is not yet an arrear.
        invoices.post(workspaceId, tenancyId,
            List.of(new InvoiceToPost(invoiceId, Component.DEPOSIT, amount)), dueDate,
            paymentReference);

        UUID depositId = UUID.randomUUID();
        deposits.charge(workspaceId, tenancyId, new DepositToCharge(depositId, invoiceId, legalForm,
            amount, rentAtCharge, assessment.multiplier(), dueDate));
        append(tenancyId, new DepositCharged(depositId, tenancyId, invoiceId, amount,
            assessment.multiplier(), rentAtCharge, legalForm));
        warnings.raise(workspaceId, tenancyId, flagsFor(assessment, amount, rentAtCharge, legalForm));
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
     * <p>Deductions are itemised against the invoices they settle rather than recorded as one total,
     * because a disputed deduction has to be traceable to the obligation it paid. Arrears beyond the
     * deposit are not forgiven by settling it; what the deposit could not cover stays owed.
     *
     * @param rentAtReturn the czynsz in force on the day of return
     * @return what actually went back to the tenant
     */
    public BigDecimal settle(UUID workspaceId, UUID tenancyId, BigDecimal rentAtReturn,
                             LocalDate returnedOn) {
        HeldDeposit deposit = held(workspaceId, tenancyId);
        var valorization = DepositValorization.compute(deposit.multiplier(),
            deposit.nominalAmount(), rentAtReturn);

        BigDecimal remaining = valorization.valorized();
        BigDecimal deducted = BigDecimal.ZERO;
        for (Invoice arrear : arrears(workspaceId, tenancyId)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal taken = remaining.min(arrear.owed());
            deposits.deduct(workspaceId, deposit.depositId(), arrear.invoiceId(), taken, returnedOn);
            invoices.applyAllocation(workspaceId, arrear.invoiceId(), taken);
            remaining = remaining.subtract(taken);
            deducted = deducted.add(taken);
        }

        deposits.settle(workspaceId, deposit.depositId(), returnedOn, rentAtReturn,
            valorization.valorized(), deducted, remaining);
        append(tenancyId, new DepositSettled(deposit.depositId(), tenancyId,
            valorization.valorized(), deducted, remaining, rentAtReturn,
            valorization.floorApplied()));
        return remaining;
    }

    /**
     * The deposit this workspace is actually holding for the tenancy.
     *
     * <p>Scoped by workspace and required to have been paid. A charge nobody settled is not money in
     * hand, and valorizing it would invent funds — so the unpaid case refuses rather than returning
     * a figure that looks like a settlement.
     */
    private HeldDeposit held(UUID workspaceId, UUID tenancyId) {
        var deposit = deposits.find(workspaceId, tenancyId).orElseThrow(
            () -> new DepositNotHeldException(tenancyId, "none was charged in this workspace"));
        if (deposit.settled()) {
            throw new IllegalStateException("the deposit for tenancy " + tenancyId
                + " has already been returned; returning it again would pay the tenant twice");
        }
        if (!deposit.isPaid()) {
            throw new DepositNotHeldException(tenancyId,
                "it was charged but never paid, so there is nothing to give back");
        }
        return deposit;
    }

    /**
     * What the tenancy still owes, oldest first. The deposit charge is not one of its own arrears.
     *
     * <p>The order is decided here rather than in SQL: which obligation a deposit reaches first is
     * policy, and it must not vary with the store. The tie-break on id keeps two charges of the same
     * day from being deducted in whatever order a row happened to come back in — a refund that
     * differs run to run is not a refund anyone can explain.
     */
    private List<Invoice> arrears(UUID workspaceId, UUID tenancyId) {
        var open = new ArrayList<>(invoices.openInvoices(workspaceId, tenancyId));
        open.removeIf(invoice -> invoice.component() == Component.DEPOSIT);
        open.sort(Comparator.comparing(Invoice::dueDate).thenComparing(Invoice::invoiceId));
        return open;
    }

    /** The assessment's answers, said to a human. */
    private static List<WarningToRaise> flagsFor(DepositAssessment assessment, BigDecimal amount,
                                                 BigDecimal rentAtCharge, String legalForm) {
        var raised = new ArrayList<WarningToRaise>();
        if (!assessment.capCheckable()) {
            raised.add(new WarningToRaise(WarningKind.DEPOSIT_CAP_UNCHECKABLE,
                "kaucja " + amount + " zł; czynsz wynosi 0, więc nie sprawdzono ustawowego limitu"));
        }
        if (!assessment.formRecognised()) {
            raised.add(new WarningToRaise(WarningKind.UNKNOWN_LEGAL_FORM,
                "nieznana forma najmu \"" + legalForm + "\"; nie sprawdzono limitu kaucji"));
        }
        if (assessment.capExceeded()) {
            raised.add(new WarningToRaise(WarningKind.DEPOSIT_CAP_EXCEEDED,
                "kaucja " + amount + " zł to " + assessment.multiplier() + "-krotność czynszu "
                    + rentAtCharge + " zł; ustawowy limit dla formy " + assessment.formName()
                    + " to " + assessment.capInMonths() + "-krotność"));
        }
        return List.copyOf(raised);
    }

    private void append(UUID tenancyId, Object event) {
        var stream = store.load(tenancyId, "TenancyLedger");
        store.append(tenancyId, "TenancyLedger", stream.version(), List.of(event), List.of());
    }
}
