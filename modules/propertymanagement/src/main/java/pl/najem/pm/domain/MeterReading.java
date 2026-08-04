package pl.najem.pm.domain;

import java.math.BigDecimal;

/** PM's own reading. The contracts/ MeterReading is a separate type; the adapter maps. */
public record MeterReading(String meterId, String utility, BigDecimal reading) {
}
