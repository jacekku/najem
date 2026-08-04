package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    public record RentChangeScheduled(UUID workspaceId, UUID tenancyId, LocalDate decidedOn,
                                      LocalDate effectiveFrom, MonthlyAmount monthly,
                                      ChangeType type) {
    }

    public record RentChangeCancelled(UUID workspaceId, UUID tenancyId, LocalDate effectiveFrom) {
    }

    /** The moment the new rent becomes the rent in force — what Accounting charges against. */
    public record RentChangeApplied(UUID workspaceId, UUID tenancyId, LocalDate effectiveFrom,
                                    MonthlyAmount monthly, ChangeType type) {
    }

    /**
     * Somebody has given notice. This does not end the tenancy — it moves the date on which it
     * will end, which is what the ending-soon timer re-arms against. {@code ground} is free text
     * because the statutory grounds are a compliance question, not a PM one.
     */
    public record TerminationNoticeGiven(UUID workspaceId, UUID tenancyId, String ground,
                                         LocalDate noticeDate, LocalDate effectiveDate,
                                         String noticeDocRef) {
    }

    /**
     * Flat rather than wrapping the EndTenancy command. A command and an event have different
     * lifecycles: adding a field to the command would silently change the shape of every event
     * already stored. HandoverProtocol is embedded because it is a value object, not a command.
     */
    public record TenancyEnded(UUID workspaceId, UUID tenancyId, LocalDate endDate,
                               LocalDate vacateDate, EndReason reasonType, String comment,
                               boolean backToMarket) {
    }

    /** Prompt, not a transition: the tenancy is still active and may yet be renewed. */
    public record TenancyEndingSoon(UUID workspaceId, UUID tenancyId, LocalDate endDate) {
    }

    public record TenancyCommentAdded(UUID workspaceId, UUID tenancyId, String text) {
    }

    /**
     * A correction states what a field should always have said. It is not a change of terms —
     * that is a rent change or an annex — so it carries no effective date.
     */
    public record TenancyDetailsCorrected(UUID workspaceId, UUID tenancyId,
                                          Map<String, String> corrections) {
    }

    /** validFrom/validTo are null for documents that do not expire, which is most of them. */
    public record TenancyDocumentAttached(UUID workspaceId, UUID tenancyId, DocType docType,
                                          String s3Ref, LocalDate validFrom, LocalDate validTo,
                                          LocalDate date) {
    }
}
