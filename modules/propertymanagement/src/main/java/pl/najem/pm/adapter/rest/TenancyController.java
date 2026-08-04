package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.TenancyService;
import pl.najem.pm.domain.ChangeType;
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

    /** Default rent day, per the domain model's stated assumption (hotspot #3). */
    private static final int DEFAULT_RENT_DAY = 10;

    private final TenancyService tenancies;

    public TenancyController(TenancyService tenancies) {
        this.tenancies = tenancies;
    }

    @PostMapping
    public Map<String, Object> reserve(@RequestBody ReserveRequest request) {
        var reservation = tenancies.reserve(toCommand(request));
        return Map.of("tenancyId", reservation.tenancyId(), "warnings", reservation.warnings());
    }

    @PostMapping("/{tenancyId}/activate")
    public void activate(@PathVariable UUID tenancyId, @RequestBody ActivateRequest request) {
        tenancies.activate(tenancyId, request.activatedOn());
    }

    @PostMapping("/{tenancyId}/cancel")
    public void cancel(@PathVariable UUID tenancyId, @RequestBody(required = false) CancelRequest request) {
        tenancies.cancelReservation(tenancyId, request == null ? "" : request.reason());
    }

    @PostMapping("/{tenancyId}/tenants")
    public void addTenant(@PathVariable UUID tenancyId, @RequestBody ContactRequest request) {
        tenancies.addTenant(tenancyId, request.contactId());
    }

    @PostMapping("/{tenancyId}/tenants/{contactId}/remove")
    public void removeTenant(@PathVariable UUID tenancyId, @PathVariable UUID contactId) {
        tenancies.removeTenant(tenancyId, contactId);
    }

    @PostMapping("/{tenancyId}/rent-changes")
    public void scheduleRentChange(@PathVariable UUID tenancyId,
                                   @RequestBody RentChangeRequest request) {
        MonthlyAmount.Breakdown breakdown = null;
        if (request.rent() != null || request.adminFee() != null || request.mediaAdvance() != null) {
            breakdown = new MonthlyAmount.Breakdown(
                orZero(request.rent()), orZero(request.adminFee()), orZero(request.mediaAdvance()));
        }
        tenancies.scheduleRentChange(tenancyId, request.decidedOn(), request.effectiveFrom(),
            new MonthlyAmount(request.monthlyTotal(), breakdown),
            ChangeType.valueOf(request.changeType().toUpperCase().replace('-', '_')));
    }

    @PostMapping("/{tenancyId}/rent-changes/{effectiveFrom}/cancel")
    public void cancelRentChange(@PathVariable UUID tenancyId,
                                 @PathVariable LocalDate effectiveFrom) {
        tenancies.cancelRentChange(tenancyId, effectiveFrom);
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

    private static LegalForm legalFormOf(String wireName) {
        return wireName == null ? LegalForm.ZWYKLY : LegalForm.valueOf(wireName.toUpperCase());
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
