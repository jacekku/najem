package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.TenancyService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pm/tenancies")
public class TenancyController {

    public record ReserveRequest(UUID unitId, LocalDate startDate, LocalDate endDate,
                                 BigDecimal monthlyRent, String paymentReference) {}
    public record ActivateRequest(LocalDate activatedOn) {}
    public record CancelRequest(String reason) {}

    private final TenancyService tenancies;

    public TenancyController(TenancyService tenancies) {
        this.tenancies = tenancies;
    }

    @PostMapping
    public Map<String, UUID> reserve(@RequestBody ReserveRequest request) {
        return Map.of("tenancyId", tenancies.reserve(request.unitId(), request.startDate(),
            request.endDate(), request.monthlyRent(), request.paymentReference()));
    }

    @PostMapping("/{tenancyId}/cancel")
    public void cancel(@PathVariable UUID tenancyId, @RequestBody(required = false) CancelRequest request) {
        tenancies.cancelReservation(tenancyId, request == null ? "" : request.reason());
    }

    @PostMapping("/{tenancyId}/activate")
    public void activate(@PathVariable UUID tenancyId, @RequestBody ActivateRequest request) {
        tenancies.activate(tenancyId, request.activatedOn());
    }
}
