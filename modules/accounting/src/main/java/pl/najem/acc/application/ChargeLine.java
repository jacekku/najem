package pl.najem.acc.application;

import pl.najem.acc.domain.Component;

import java.math.BigDecimal;

/** One component-typed claim against a tenancy — the reconciliation atom. */
public record ChargeLine(Component component, BigDecimal amount) {
}
