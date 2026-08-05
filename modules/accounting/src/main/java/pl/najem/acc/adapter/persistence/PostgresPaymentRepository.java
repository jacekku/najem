package pl.najem.acc.adapter.persistence;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.acc.application.PaymentRepository;
import pl.najem.acc.domain.Payment;

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
            select unallocated_amount from acc_payment where workspace_id = ? and payment_id = ?
            """, (rs, i) -> new Payment(paymentId, rs.getBigDecimal(1)), workspaceId, paymentId)));
    }

    @Override
    public void recordSettlement(UUID workspaceId, Payment payment) {
        jdbc.update("""
            update acc_payment set unallocated_amount = ?, status = ?
            where workspace_id = ? and payment_id = ?
            """, payment.remaining(), payment.status().wireName(), workspaceId, payment.paymentId());
    }
}
