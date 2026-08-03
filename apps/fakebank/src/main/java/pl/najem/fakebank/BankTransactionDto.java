package pl.najem.fakebank;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BankTransactionDto(String id, BigDecimal amount, String title, LocalDate bookingDate) {
}
