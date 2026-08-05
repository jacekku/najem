package pl.najem.acc.application;

import pl.najem.acc.domain.Payment;
import pl.najem.acc.domain.PaymentStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link PaymentRepository} kept in a map, so the settlement rules can be exercised without a
 * database behind them.
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    /** A payment as this repository stores it — the columns, not the entity. */
    public record Stored(BigDecimal unallocatedAmount, PaymentStatus status) {}

    private final Map<Key, Stored> payments = new HashMap<>();

    private record Key(UUID workspaceId, UUID paymentId) {}

    /** Puts money on the table: a payment that has arrived and settled nothing yet. */
    public void arrived(UUID workspaceId, UUID paymentId, BigDecimal amount) {
        payments.put(new Key(workspaceId, paymentId), new Stored(amount, PaymentStatus.UNMATCHED));
    }

    public Stored find(UUID workspaceId, UUID paymentId) {
        return payments.get(new Key(workspaceId, paymentId));
    }

    @Override
    public Optional<Payment> getPayment(UUID workspaceId, UUID paymentId) {
        return Optional.ofNullable(payments.get(new Key(workspaceId, paymentId)))
            .map(stored -> new Payment(paymentId, stored.unallocatedAmount()));
    }

    @Override
    public void recordSettlement(UUID workspaceId, Payment payment) {
        payments.put(new Key(workspaceId, payment.paymentId()),
            new Stored(payment.remaining(), payment.status()));
    }
}
