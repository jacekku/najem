package pl.najem.pm.application;

import pl.najem.pm.domain.InspectionType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An inspection whose statutory deadline has passed, as a manager's attention list shows it.
 *
 * <p>Carries the address, which lives on the property rather than the inspection, because the
 * person reading this is looking for a building and an inspection id would not tell them which.
 *
 * <p>What is deliberately absent is how overdue it is. That is a subtraction against a date the
 * caller chose, and putting it here would fix an answer computed at query time to a row that
 * outlives the question.
 */
public record OverdueInspection(UUID propertyId, String address, InspectionType type,
                                LocalDate performedOn, LocalDate nextDueOn) {
}
