package pl.najem.pm.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Legal basis of settlement (art. 6c). Replaces the free-form damage-protocol checklist item.
 * The move-out protocol's readings feed Accounting's media true-up, and its date is one of the
 * two inputs to their deposit-settlement deadline — so this is a typed step, not a note.
 */
public record HandoverProtocol(ChecklistPhase type, List<MeterReading> meterReadings,
                               String conditionNotes, List<String> photoRefs,
                               String signedDocRef, LocalDate date) {
}
