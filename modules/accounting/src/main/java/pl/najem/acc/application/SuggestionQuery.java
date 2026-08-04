package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>So the tier comes back as a number this module owns, and with it the facts the tier is an
 * assertion about. <strong>The evidence matters as much as the confidence.</strong> A tier without
 * what it was derived from is just a number, and a manager cannot check a number.
 */
@Service
@Transactional(readOnly = true)
public class SuggestionQuery {

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
    public record Row(UUID paymentId, UUID chargeId, UUID tenancyId, int tier,
                      BigDecimal paidAmount, BigDecimal chargedAmount, BigDecimal outstanding,
                      LocalDate paidOn, LocalDate dueDate, String component,
                      String quotedReference, String expectedReference,
                      String payerName, String payerIban) {

        /** Whether confirming this would leave the charge still partly unpaid. */
        public boolean isPartPayment() {
            return paidAmount.compareTo(outstanding) < 0;
        }
    }

    private final JdbcTemplate jdbc;

    public SuggestionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every suggestion awaiting a decision, most confident first.
     *
     * <p>Ordered by tier so the certainties are dealt with before the guesses. A manager working
     * down the list is then spending their attention where it is actually needed, and the ordering
     * carries the same message as the tier itself.
     */
    public List<Row> forWorkspace(UUID workspaceId) {
        return jdbc.query("""
            select s.payment_id, s.charge_id, c.tenancy_id, s.tier,
                   p.amount, c.amount, c.amount - c.allocated_amount,
                   p.booking_date, c.due_date, c.component,
                   p.title, c.payment_reference, p.counterparty_name, p.counterparty_iban
            from acc_suggestion s
            join acc_payment p on p.payment_id = s.payment_id
            join acc_charge  c on c.charge_id  = s.charge_id
            where s.workspace_id = ?
            order by s.tier, p.booking_date, s.payment_id
            """, (rs, i) -> new Row(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getInt(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                rs.getDate(8).toLocalDate(), rs.getDate(9).toLocalDate(), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14)),
            workspaceId);
    }
}
