package pl.najem.pm.domain;

import java.time.LocalDate;

/** Bitemporal: decided on one day, effective from another. */
public record RentChange(LocalDate decidedOn, LocalDate effectiveFrom, MonthlyAmount monthly,
                         ChangeType type) {
}
