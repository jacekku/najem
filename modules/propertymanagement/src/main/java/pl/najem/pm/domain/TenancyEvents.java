package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Tenancy-stream events. First field is always workspaceId. */
public final class TenancyEvents {

    private TenancyEvents() {
    }

    public record TenancyReserved(UUID workspaceId, UUID tenancyId, UUID unitId,
                                  List<UUID> tenantContactIds, List<UUID> guarantorContactIds,
                                  LocalDate startDate, LocalDate endDate, LegalForm legalForm,
                                  MonthlyAmount monthly, int rentDay, BigDecimal depositAmount,
                                  String paymentReference) {
    }

    public record TenantAddedToTenancy(UUID workspaceId, UUID tenancyId, UUID contactId) {
    }

    public record TenantRemovedFromTenancy(UUID workspaceId, UUID tenancyId, UUID contactId) {
    }

    public record TenancyReservationCancelled(UUID workspaceId, UUID tenancyId, String reason) {
    }

    public record TenancyActivated(UUID workspaceId, UUID tenancyId, LocalDate activatedOn) {
    }

    public record ChecklistItemAdded(UUID workspaceId, UUID tenancyId, String key,
                                     ChecklistPhase phase) {
    }

    public record ChecklistItemCompleted(UUID workspaceId, UUID tenancyId, String key) {
    }

    public record HandoverProtocolRecorded(UUID workspaceId, UUID tenancyId,
                                           HandoverProtocol protocol) {
    }
}
