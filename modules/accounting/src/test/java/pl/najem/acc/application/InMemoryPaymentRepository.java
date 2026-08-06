package pl.najem.acc.application;

import pl.najem.acc.domain.Payment;
import pl.najem.acc.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link PaymentRepository} kept in a map, so the settlement rules can be exercised without a
 * database behind them.
 */
public class InMemoryPaymentRepository implements PaymentRepository {

    /**
     * A payment as this repository stores it — the columns, not the entity.
     *
     * <p>The bank's own description is carried because the suspense queue shows it to a human. A
     * payment that arrived through {@link #arrived} has none, which is what a test that does not
     * care about the queue looks like.
     */
    public record Stored(BigDecimal unallocatedAmount, PaymentStatus status, String externalId,
                         String title, String counterpartyName, String counterpartyIban,
                         String direction, String currency, LocalDate bookingDate) {
    }

    private final Map<Key, Stored> payments = new LinkedHashMap<>();

    private record Key(UUID workspaceId, UUID paymentId) {}

    /** Puts money on the table: a payment that has arrived and settled nothing yet. */
    public void arrived(UUID workspaceId, UUID paymentId, BigDecimal amount) {
        arrived(workspaceId, paymentId, amount, LocalDate.EPOCH, "");
    }

    /** The same, said the way the bank said it, for the tests that read the queue. */
    public void arrived(UUID workspaceId, UUID paymentId, BigDecimal amount, LocalDate bookedOn,
                        String title) {
        payments.put(new Key(workspaceId, paymentId), new Stored(amount, PaymentStatus.UNMATCHED,
            paymentId.toString(), title, null, null, "in", "PLN", bookedOn));
    }

    /** A transfer whose sender the bank named, which is what tier 3 is ever able to learn from. */
    public void arrivedFrom(UUID workspaceId, UUID paymentId, BigDecimal amount, String payerIban) {
        payments.put(new Key(workspaceId, paymentId), new Stored(amount, PaymentStatus.UNMATCHED,
            paymentId.toString(), "", null, payerIban, "in", "PLN", LocalDate.EPOCH));
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
        Stored stored = payments.get(new Key(workspaceId, payment.paymentId()));
        payments.put(new Key(workspaceId, payment.paymentId()),
            new Stored(payment.remaining(), payment.status(), stored.externalId(), stored.title(),
                stored.counterpartyName(), stored.counterpartyIban(), stored.direction(),
                stored.currency(), stored.bookingDate()));
    }

    /**
     * Mirrors the statement's own two conditions — money left, and not judged non-tenant — rather
     * than keeping a separate queue. The whole point of the suspense list is that it is derived, and
     * a fake holding its own copy could agree with itself while the query disagreed.
     */
    @Override
    public List<UnrestedPayment> unrested(UUID workspaceId) {
        return payments.entrySet().stream()
            .filter(entry -> entry.getKey().workspaceId().equals(workspaceId))
            .filter(entry -> entry.getValue().unallocatedAmount().signum() > 0)
            .filter(entry -> entry.getValue().status() != PaymentStatus.NON_TENANT)
            .map(entry -> new UnrestedPayment(entry.getKey().paymentId(),
                entry.getValue().externalId(), entry.getValue().unallocatedAmount(),
                entry.getValue().title(), entry.getValue().counterpartyName(),
                entry.getValue().direction(), entry.getValue().currency(),
                entry.getValue().bookingDate()))
            .sorted(Comparator.comparing(UnrestedPayment::bookingDate)
                .thenComparing(UnrestedPayment::externalId))
            .toList();
    }

    /**
     * The unallocated amount is deliberately left alone, as the update leaves it: a non-tenant line
     * keeps the figure the bank reported. It leaves the queue because of its status, not because
     * the money was zeroed.
     */
    @Override
    public boolean markNonTenant(UUID workspaceId, UUID paymentId, String reason,
                                 LocalDate classifiedOn) {
        Stored stored = payments.get(new Key(workspaceId, paymentId));
        if (stored == null) {
            return false;
        }
        payments.put(new Key(workspaceId, paymentId),
            new Stored(stored.unallocatedAmount(), PaymentStatus.NON_TENANT, stored.externalId(),
                stored.title(), stored.counterpartyName(), stored.counterpartyIban(),
                stored.direction(), stored.currency(), stored.bookingDate()));
        return true;
    }

    @Override
    public Optional<PaymentStatus> statusOf(UUID workspaceId, UUID paymentId) {
        return Optional.ofNullable(payments.get(new Key(workspaceId, paymentId))).map(Stored::status);
    }

    /** Nothing is left as credit: money that never arrived is nobody's. */
    @Override
    public void reverse(UUID workspaceId, UUID paymentId, String reason, LocalDate reversedOn) {
        Stored stored = payments.get(new Key(workspaceId, paymentId));
        payments.put(new Key(workspaceId, paymentId),
            new Stored(BigDecimal.ZERO, PaymentStatus.REVERSED, stored.externalId(), stored.title(),
                stored.counterpartyName(), stored.counterpartyIban(), stored.direction(),
                stored.currency(), stored.bookingDate()));
    }

    /** The status is deliberately untouched, as the update leaves it: only the money moves back. */
    @Override
    public void returnUnallocated(UUID workspaceId, UUID paymentId, BigDecimal amount) {
        Stored stored = payments.get(new Key(workspaceId, paymentId));
        payments.put(new Key(workspaceId, paymentId),
            new Stored(stored.unallocatedAmount().add(amount), stored.status(), stored.externalId(),
                stored.title(), stored.counterpartyName(), stored.counterpartyIban(),
                stored.direction(), stored.currency(), stored.bookingDate()));
    }

    /**
     * Absent when the bank named no sender, as the {@code is not null} clause makes it — a statement
     * format that carries no counterparty must teach the payer register nothing.
     */
    @Override
    public Optional<String> payerAccountOf(UUID workspaceId, UUID paymentId) {
        return Optional.ofNullable(payments.get(new Key(workspaceId, paymentId)))
            .map(Stored::counterpartyIban);
    }

    @Override
    public boolean alreadyIngested(UUID workspaceId, String externalId) {
        return payments.entrySet().stream()
            .filter(entry -> entry.getKey().workspaceId().equals(workspaceId))
            .anyMatch(entry -> entry.getValue().externalId().equals(externalId));
    }

    /** All of it unplaced, as the insert holds it: nothing has been decided about this line yet. */
    @Override
    public void record(UUID workspaceId, UUID paymentId, BankLine line) {
        payments.put(new Key(workspaceId, paymentId), new Stored(line.amount(),
            PaymentStatus.UNMATCHED, line.externalId(), line.title(), line.counterpartyName(),
            line.counterpartyIban(), line.creditDebitIndicator(), line.currency(),
            line.bookingDate()));
    }

    /** Only the status moves, as the update does — the money has not been placed, just proposed. */
    @Override
    public void markSuggested(UUID workspaceId, UUID paymentId) {
        Stored stored = payments.get(new Key(workspaceId, paymentId));
        payments.put(new Key(workspaceId, paymentId),
            new Stored(stored.unallocatedAmount(), PaymentStatus.SUGGESTED, stored.externalId(),
                stored.title(), stored.counterpartyName(), stored.counterpartyIban(),
                stored.direction(), stored.currency(), stored.bookingDate()));
    }
}
