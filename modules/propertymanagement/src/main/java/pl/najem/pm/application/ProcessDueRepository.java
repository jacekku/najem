package pl.najem.pm.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Durable timers for the PM process managers — rows, not an in-memory schedule, so a restart never
 * loses a pending activation and a repeated sweep never fires one twice.
 *
 * <p>{@code Repository}, not {@code Projection}: pm_process_due is the record. Nothing rebuilds it
 * from an event stream — an armed timer is a fact this module decided and stored, and dropping the
 * table would lose it. A7 reserves {@code Projection} for a store that could be thrown away, and
 * this one could not.
 *
 * <p>A timer is keyed {@code (kind, subjectId)}: one pending activation per tenancy, one pending
 * rent change per tenancy. That is deliberate and load-bearing — see
 * {@code TenancyService.armNextRentChange}, which arms the EARLIEST pending change precisely
 * because a later one would otherwise overwrite an earlier one's row and strand it.
 */
public interface ProcessDueRepository {

    /** Re-arming an already-fired timer clears the fired mark — used when a due date moves. */
    void arm(String kind, UUID subjectId, LocalDate dueOn);

    /** Subjects whose timer of this kind is due on or before {@code on} and has not fired. */
    List<UUID> due(String kind, LocalDate on);

    void markFired(String kind, UUID subjectId);

    void disarm(String kind, UUID subjectId);
}
