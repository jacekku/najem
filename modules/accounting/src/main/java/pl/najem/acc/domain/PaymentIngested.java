package pl.najem.acc.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentIngested(UUID paymentId, String externalId, BigDecimal amount,
                              String title, LocalDate bookingDate) {
}
