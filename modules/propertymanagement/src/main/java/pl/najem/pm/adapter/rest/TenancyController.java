package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.application.WorkspaceGuard;
import pl.najem.pm.domain.ChangeType;
import pl.najem.pm.domain.DocType;
import pl.najem.pm.domain.EndReason;
import pl.najem.pm.domain.EndTenancy;
import pl.najem.pm.domain.LegalForm;
import pl.najem.pm.domain.MonthlyAmount;
import pl.najem.pm.domain.ReserveTenancy;
import pl.najem.pm.domain.Term;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pm/tenancies")
public class TenancyController {

    /** Wire DTOs live in the adapter — external JSON never becomes a domain record directly. */
    public record ReserveRequest(UUID unitId, List<UUID> tenantContactIds,
                                 List<UUID> guarantorContactIds, LocalDate startDate,
                                 LocalDate endDate, String legalForm, BigDecimal monthlyTotal,
                                 BigDecimal rent, BigDecimal adminFee, BigDecimal mediaAdvance,
                                 Integer rentDay, BigDecimal depositAmount, String paymentReference) {}

    public record ActivateRequest(LocalDate activatedOn) {}

    public record CancelRequest(String reason) {}

    public record ContactRequest(UUID contactId) {}

    public record RentChangeRequest(LocalDate decidedOn, LocalDate effectiveFrom, String changeType,
                                    BigDecimal monthlyTotal, BigDecimal rent, BigDecimal adminFee,
                                    BigDecimal mediaAdvance) {}

    public record NoticeRequest(String ground, LocalDate noticeDate, LocalDate effectiveDate,
                                String noticeDocRef) {}

    public record EndRequest(LocalDate endDate, LocalDate vacateDate, String reasonType,
                             String comment, Boolean backToMarket) {}

    public record CommentRequest(String text) {}

    public record DocumentRequest(String docType, String s3Ref, LocalDate validFrom,
                                  LocalDate validTo, LocalDate date) {}

    /** Default rent day, per the domain model's stated assumption (hotspot #3). */
    private static final int DEFAULT_RENT_DAY = 10;

    private final TenancyService tenancies;
    private final WorkspaceGuard guard;

    public TenancyController(TenancyService tenancies, WorkspaceGuard guard) {
        this.tenancies = tenancies;
        this.guard = guard;
    }

    @PostMapping
    public Map<String, Object> reserve(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                                       @RequestBody ReserveRequest request) {
        guard.requireUnit(workspaceId, request.unitId());
        var reservation = tenancies.reserve(toCommand(request));
        return Map.of("tenancyId", reservation.tenancyId(), "warnings", reservation.warnings());
    }

    @PostMapping("/{tenancyId}/activate")
    public Map<String, Object> activate(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId,
                                        @RequestBody ActivateRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        return Map.of("warnings", tenancies.activate(tenancyId, request.activatedOn()));
    }

    @PostMapping("/{tenancyId}/cancel")
    public void cancel(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody(required = false) CancelRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.cancelReservation(tenancyId, request == null ? "" : request.reason());
    }

    @PostMapping("/{tenancyId}/tenants")
    public void addTenant(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody ContactRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.addTenant(tenancyId, request.contactId());
    }

    @PostMapping("/{tenancyId}/tenants/{contactId}/remove")
    public void removeTenant(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @PathVariable UUID contactId) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.removeTenant(tenancyId, contactId);
    }

    @PostMapping("/{tenancyId}/rent-changes")
    public Map<String, Object> scheduleRentChange(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId,
                                                  @RequestBody RentChangeRequest request) {
        MonthlyAmount.Breakdown breakdown = null;
        if (request.rent() != null || request.adminFee() != null || request.mediaAdvance() != null) {
            breakdown = new MonthlyAmount.Breakdown(
                orZero(request.rent()), orZero(request.adminFee()), orZero(request.mediaAdvance()));
        }
        guard.requireTenancy(workspaceId, tenancyId);
        return Map.of("warnings", tenancies.scheduleRentChange(tenancyId, request.decidedOn(),
            request.effectiveFrom(), new MonthlyAmount(request.monthlyTotal(), breakdown),
            ChangeType.valueOf(request.changeType().toUpperCase().replace('-', '_'))));
    }

    @PostMapping("/{tenancyId}/rent-changes/{effectiveFrom}/cancel")
    public void cancelRentChange(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId,
                                 @PathVariable LocalDate effectiveFrom) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.cancelRentChange(tenancyId, effectiveFrom);
    }

    @PostMapping("/{tenancyId}/termination-notice")
    public void giveTerminationNotice(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId,
                                      @RequestBody NoticeRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.giveTerminationNotice(tenancyId, request.ground(), request.noticeDate(),
            request.effectiveDate(), request.noticeDocRef());
    }

    @PostMapping("/{tenancyId}/end")
    public void end(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody EndRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.end(tenancyId, new EndTenancy(request.endDate(), request.vacateDate(),
            endReasonOf(request.reasonType()), request.comment(),
            // Absent means "not back to market": reopening a unit is an explicit decision, and
            // a missing field must not silently advertise a flat the manager said nothing about.
            Boolean.TRUE.equals(request.backToMarket())));
    }

    @PostMapping("/{tenancyId}/comments")
    public void addComment(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody CommentRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.addComment(tenancyId, request.text());
    }

    @PostMapping("/{tenancyId}/corrections")
    public Map<String, Object> correctDetails(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId,
                                              @RequestBody Map<String, String> corrections) {
        guard.requireTenancy(workspaceId, tenancyId);
        return Map.of("warnings", tenancies.correctDetails(tenancyId, corrections));
    }

    @PostMapping("/{tenancyId}/documents")
    public void attachDocument(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody DocumentRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        tenancies.attachDocument(tenancyId, docTypeOf(request.docType()), request.s3Ref(),
            request.validFrom(), request.validTo(), request.date());
    }

    private static DocType docTypeOf(String wireName) {
        return wireName == null ? DocType.OTHER
            : DocType.valueOf(wireName.toUpperCase().replace('-', '_'));
    }

    private static EndReason endReasonOf(String wireName) {
        return EndReason.valueOf(wireName.toUpperCase().replace('-', '_'));
    }

    private static ReserveTenancy toCommand(ReserveRequest request) {
        // A split exists only when the caller actually sends components. Sending none means
        // "no contractual split" — the collapse rule — not "a split that happens to be zero".
        MonthlyAmount.Breakdown breakdown = null;
        if (request.rent() != null || request.adminFee() != null || request.mediaAdvance() != null) {
            breakdown = new MonthlyAmount.Breakdown(
                orZero(request.rent()), orZero(request.adminFee()), orZero(request.mediaAdvance()));
        }
        Term term = request.endDate() == null
            ? new Term.Indefinite() : new Term.FixedTerm(request.endDate());
        return new ReserveTenancy(null, null, request.unitId(),
            request.tenantContactIds() == null ? List.of() : request.tenantContactIds(),
            request.guarantorContactIds() == null ? List.of() : request.guarantorContactIds(),
            request.startDate(), term, legalFormOf(request.legalForm()),
            new MonthlyAmount(request.monthlyTotal(), breakdown),
            request.rentDay() == null ? DEFAULT_RENT_DAY : request.rentDay(),
            request.depositAmount(), request.paymentReference());
    }

    /** No default: see Tenancy.reserve. A missing legal form is a 400, not a zwykły tenancy. */
    private static LegalForm legalFormOf(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("legalForm is required");
        }
        return LegalForm.valueOf(wireName.toUpperCase());
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
