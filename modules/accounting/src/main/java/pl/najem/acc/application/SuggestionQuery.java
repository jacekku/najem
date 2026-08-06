package pl.najem.acc.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reading the reconciliation ladder's suggestions, with the evidence each one rests on.
 *
 * <p>The read used to return {@code payment_id} and {@code charge_id} and nothing else, so a manager
 * confirming a match was confirming two opaque identifiers. That defeats the point of the ladder: a
 * tier-1 certainty (the payment quotes the reference exactly) and a tier-4 guess are not worth the
 * same confidence, and if they look alike on screen the manager either trusts all of them or checks
 * all of them — and both of those are the same as having no tiers at all.
 *
 * <p>Neither a repository nor a projection, and the name says which: acc_suggestion is
 * the record and {@link SuggestionRepository} owns it, while this assembles a view by joining that
 * record to the payment and the charge it names. There is no derived table to rebuild, so
 * {@code Projection} would be a claim about storage that is not true here. It is a query.
 *
 * <p>So the tier comes back as a number this module owns, and with it the facts the tier is an
 * assertion about. <strong>The evidence matters as much as the confidence.</strong> A tier without
 * what it was derived from is just a number, and a manager cannot check a number.
 */
public interface SuggestionQuery {

    /**
     * One suggested match, and why it was suggested.
     *
     * <p>{@code paidAmount} and {@code outstanding} are both present because they are the pair that
     * decides whether a confirmation settles the charge or only part of it. Tiers 2 and above do not
     * constrain the amount at all — they match on the reference or on a remembered payer — so a
     * suggestion may well be a part payment, and that has to be visible <em>before</em> the click
     * rather than discovered after it.
     *
     * <p>{@code quotedReference} is what the payer actually typed, and {@code expectedReference} is
     * what the charge asked for. Showing both lets a manager see what the ladder saw: at tier 1 they
     * are equal, and at tier 2 the difference is the whole judgement being delegated to them.
     *
     * @param tier        1 exact reference · 2 reference found within the title · 3 remembered payer
     *                    · 4 placed by hand from the suspense queue
     * @param outstanding what is still owed on the charge, before this payment is applied
     */
    record Row(UUID paymentId, UUID chargeId, UUID tenancyId, int tier,
               BigDecimal paidAmount, BigDecimal chargedAmount, BigDecimal outstanding,
               LocalDate paidOn, LocalDate dueDate, String component,
               String quotedReference, String expectedReference,
               String payerName, String payerIban) {

        /** Whether confirming this would leave the charge still partly unpaid. */
        public boolean isPartPayment() {
            return paidAmount.compareTo(outstanding) < 0;
        }
    }

    /**
     * Every suggestion awaiting a decision, most confident first.
     *
     * <p>Ordered by tier so the certainties are dealt with before the guesses. A manager working
     * down the list is then spending their attention where it is actually needed, and the ordering
     * carries the same message as the tier itself.
     */
    List<Row> forWorkspace(UUID workspaceId);
}
