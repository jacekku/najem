package pl.najem.acc.application;

import pl.najem.acc.domain.HeldDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The deposits an agency holds, and what was taken out of one when it was returned.
 *
 * <p>The obligation the tenant pays is an ordinary invoice and lives in {@link InvoiceRepository};
 * this holds what makes it a deposit — the agreed multiple, the rent it was a multiple of, and the
 * settlement.
 */
public interface DepositRepository {

    /** Records a deposit against the invoice the tenant will pay it through. */
    void charge(UUID workspaceId, UUID tenancyId, DepositToCharge deposit);

    /**
     * The one deposit of that tenancy in that workspace, whatever state it is in — the caller
     * decides what disqualifies it, because "already returned" and "never paid" are different
     * refusals.
     *
     * <p>There can only be one: {@code acc_deposit} is unique on {@code (workspace_id, tenancy_id)},
     * so a redelivered activation — which the at-least-once outbox is entitled to produce — is
     * refused by the database rather than producing a second deposit with a different amount and a
     * different answer to what the tenant gets back. That constraint is asserted by name in
     * {@code AccountingSchemaShapeTest}, so dropping it fails a test rather than silently making a
     * refund depend on row order.
     */
    Optional<HeldDeposit> find(UUID workspaceId, UUID tenancyId);

    /**
     * Files one deduction against the invoice it settled. Itemised rather than totalled, because a
     * disputed deduction has to be traceable to the obligation it paid.
     */
    void deduct(UUID workspaceId, UUID depositId, UUID invoiceId, BigDecimal amount,
                LocalDate deductedOn);

    /** Closes the deposit. After this it is no longer held and cannot be returned again. */
    void settle(UUID workspaceId, UUID depositId, LocalDate returnedOn, BigDecimal rentAtReturn,
                BigDecimal valorized, BigDecimal deducted, BigDecimal returned);
}
