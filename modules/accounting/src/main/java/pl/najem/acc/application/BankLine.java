package pl.najem.acc.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BankLine(String externalId, BigDecimal amount, String title, LocalDate bookingDate) {
}
