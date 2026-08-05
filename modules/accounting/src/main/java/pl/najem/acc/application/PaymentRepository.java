package pl.najem.acc.application;

import pl.najem.acc.domain.Payment;

import java.util.Optional;
import java.util.UUID;

/**
 * Payments as the accounting domain needs them: what arrived and how much of it has not yet come
 * to rest against an obligation.
 */
public interface PaymentRepository {

    /**
     * The payment, holding what is still the tenant's credit rather than settled.
     *
     * <p>Empty when no such payment exists in that workspace. Absence is the repository's answer,
     * not its problem: whether a missing payment is an error depends on what the caller was doing,
     * so the caller decides.
     */
    Optional<Payment> getPayment(UUID workspaceId, UUID paymentId);

    /** Records where the payment came to rest once allocation has run. */
    void recordSettlement(UUID workspaceId, Payment payment);
}
