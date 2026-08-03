package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentAllocated(UUID paymentId, UUID chargeId, BigDecimal amount) {
}
