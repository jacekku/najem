package pl.najem.contracts.events;

import java.math.BigDecimal;

public record MeterReading(String meterId, String utility, BigDecimal reading) {
}
