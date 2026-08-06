package pl.najem.pm.adapter.rest;

import java.time.LocalDate;
import java.util.UUID;

/** What the repair endpoints accept and answer with, as JSON sees it. */
final class RepairWire {

    private RepairWire() {
    }

    /**
     * {@code scope} and {@code statutoryDutyHint} arrive as strings so an unknown spelling fails in
     * one named place rather than as a binding error before any handler runs — and neither is
     * defaulted, because both decide something the manager has to have said.
     */
    record ReportRequest(String scope, UUID assetId, String description, UUID causedByTenancy,
                         String statutoryDutyHint, LocalDate reportedOn) {}

    record CompleteRequest(LocalDate completedOn, String notes) {}

    /** {@code {"repairId":"…"}}, as before. */
    record RepairReported(UUID repairId) {}
}
