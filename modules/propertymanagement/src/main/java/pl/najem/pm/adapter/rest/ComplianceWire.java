package pl.najem.pm.adapter.rest;

import java.time.LocalDate;
import java.util.UUID;

/** What the compliance endpoints accept and answer with, as JSON sees it. */
final class ComplianceWire {

    private ComplianceWire() {
    }

    /**
     * {@code type} arrives as a string rather than the enum so a spelling the module does not know
     * fails as a bad request in one named place, instead of as a binding error before any handler
     * runs.
     */
    record InspectionRequest(String type, LocalDate performedOn, String reportDoc,
                             String findings) {}

    /** {@code {"inspectionId":"…"}}, as before — a named type rather than a one-entry map. */
    record InspectionRecorded(UUID inspectionId) {}
}
