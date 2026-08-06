package pl.najem.acc.adapter.persistence;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.BankLine;
import pl.najem.acc.application.PaymentRepository;
import pl.najem.acc.application.UnrestedPayment;
import pl.najem.acc.domain.Payment;
import pl.najem.acc.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link PaymentRepository} over acc_payment. */
@Repository
public class PostgresPaymentRepository implements PaymentRepository {

    private final JdbcTemplate jdbc;

    public PostgresPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Absence is an answer here, so the query is allowed to return nothing — but not to return two.
     * {@code optionalResult} raises rather than picking one, because a payment id matching twice
     * within a workspace means the key is not what this code believes it is, and quietly settling
     * against one of the pair would hide that.
     */
    @Override
    public Optional<Payment> getPayment(UUID workspaceId, UUID paymentId) {
        return Optional.ofNullable(DataAccessUtils.singleResult(jdbc.query("""
            select unallocated_amount, status from acc_payment
            where workspace_id = ? and payment_id = ?
            """, (rs, i) -> new Payment(paymentId, rs.getBigDecimal(1),
                PaymentStatus.of(rs.getString(2))), workspaceId, paymentId)));
    }

    /**
     * The ordering is part of what is asked for: a queue a human works through top to bottom must
     * not reshuffle between two readings of the same data, and external_id breaks ties within a day.
     */
    @Override
    public List<UnrestedPayment> unrested(UUID workspaceId) {
        return jdbc.query("""
            select payment_id, external_id, unallocated_amount, title, counterparty_name,
                   direction, currency, booking_date
            from acc_payment
            where workspace_id = ? and unallocated_amount > 0 and status <> 'non-tenant'
            order by booking_date, external_id
            """, (rs, i) -> new UnrestedPayment(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getDate(8).toLocalDate()), workspaceId);
    }

    @Override
    public boolean markNonTenant(UUID workspaceId, UUID paymentId, String reason,
                                 LocalDate classifiedOn) {
        return jdbc.update("""
            update acc_payment set status = 'non-tenant', non_tenant_reason = ?, classified_on = ?
            where workspace_id = ? and payment_id = ?
            """, reason, classifiedOn, workspaceId, paymentId) > 0;
    }

    @Override
    public Optional<String> payerAccountOf(UUID workspaceId, UUID paymentId) {
        return jdbc.query("""
            select counterparty_iban from acc_payment
            where workspace_id = ? and payment_id = ? and counterparty_iban is not null
            """, (rs, i) -> rs.getString(1), workspaceId, paymentId).stream().findFirst();
    }

    @Override
    public void reverse(UUID workspaceId, UUID paymentId, String reason, LocalDate reversedOn) {
        jdbc.update("""
            update acc_payment
            set status = 'reversed', unallocated_amount = 0, reversal_reason = ?, reversed_on = ?
            where workspace_id = ? and payment_id = ?
            """, reason, reversedOn, workspaceId, paymentId);
    }

    @Override
    public void returnUnallocated(UUID workspaceId, UUID paymentId, BigDecimal amount) {
        jdbc.update("""
            update acc_payment set unallocated_amount = unallocated_amount + ?
            where workspace_id = ? and payment_id = ?
            """, amount, workspaceId, paymentId);
    }

    @Override
    public void recordSettlement(UUID workspaceId, Payment payment) {
        jdbc.update("""
            update acc_payment set unallocated_amount = ?, status = ?
            where workspace_id = ? and payment_id = ?
            """, payment.remaining(), payment.status().wireName(), workspaceId, payment.paymentId());
    }

    @Override
    public boolean alreadyIngested(UUID workspaceId, String externalId) {
        return !jdbc.query("""
            select 1 from acc_payment where workspace_id = ? and external_id = ?
            """, (rs, i) -> 1, workspaceId, externalId).isEmpty();
    }

    @Override
    public void record(UUID workspaceId, UUID paymentId, BankLine line) {
        jdbc.update("""
            insert into acc_payment(payment_id, workspace_id, external_id, amount, title, booking_date,
                                    status, unallocated_amount, counterparty_name, counterparty_iban,
                                    bank_reference, value_date, direction, currency)
            values (?,?,?,?,?,?,'unmatched',?,?,?,?,?,?,?)
            """, paymentId, workspaceId, line.externalId(), line.amount(), line.title(),
            line.bookingDate(), line.amount(), line.counterpartyName(), line.counterpartyIban(),
            line.bankReference(), line.valueDate(), line.creditDebitIndicator(), line.currency());
    }

    @Override
    public void markSuggested(UUID workspaceId, UUID paymentId) {
        jdbc.update("""
            update acc_payment set status = 'suggested' where workspace_id = ? and payment_id = ?
            """, workspaceId, paymentId);
    }
}
