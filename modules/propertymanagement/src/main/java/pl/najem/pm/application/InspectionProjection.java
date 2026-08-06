package pl.najem.pm.application;

import pl.najem.pm.domain.InspectionType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The write side of {@code pm_inspection}: what the table is told once a property has recorded an
 * inspection.
 *
 * <p>A projection now in fact and not only in name. Every column, including the primary key, comes
 * off {@code InspectionCompleted}, so dropping the table and replaying the Property streams rebuilds
 * it identically — which is what the suffix claims and what was not true while the id was minted
 * beside the insert.
 *
 * <p>Write-only, and separate from {@link OverdueInspectionQuery} on purpose. One port carrying both
 * would put the overdue read within reach of every service that records an inspection, and the
 * moment a service decides something from that read the derived copy has become an opinion. This is
 * the arrangement architecture.md describes for acc_tenancy_status: the writer is what services
 * hold, the reader is what a screen holds, and two ports over one derived table is not duplication.
 */
public interface InspectionProjection {

    void inspectionRecorded(UUID inspectionId, UUID workspaceId, UUID propertyId,
                            InspectionType type, LocalDate performedOn, LocalDate nextDueOn,
                            String reportDoc, String findings);
}
