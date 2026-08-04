package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pm")
public class PortfolioController {

    /** Wire DTOs live in the adapter — never bind external JSON into a domain record. */
    public record OwnerDto(UUID contactId, BigDecimal sharePercent) {}

    public record CreatePropertyRequest(String address, List<OwnerDto> owners) {}

    public record AddUnitRequest(String name, BigDecimal baseRent) {}

    public record BaseRentRequest(BigDecimal baseRent) {}

    public record ReasonRequest(String reason) {}

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @PostMapping("/properties")
    public Map<String, UUID> createProperty(
            @RequestHeader(name = WorkspaceHeader.NAME, required = false) UUID workspaceHeader,
            @RequestBody CreatePropertyRequest request) {
        List<Owner> owners = request.owners() == null ? List.of()
            : request.owners().stream().map(o -> new Owner(o.contactId(), o.sharePercent())).toList();
        return Map.of("propertyId", portfolio.createProperty(
            WorkspaceHeader.resolve(workspaceHeader), request.address(), owners));
    }

    @PostMapping("/properties/{propertyId}/units")
    public Map<String, UUID> addUnit(@PathVariable UUID propertyId, @RequestBody AddUnitRequest request) {
        return Map.of("unitId", portfolio.addUnit(propertyId, request.name(), request.baseRent()));
    }

    @PostMapping("/units/{unitId}/base-rent")
    public void setBaseRent(@PathVariable UUID unitId, @RequestBody BaseRentRequest request) {
        portfolio.setUnitBaseRent(unitId, request.baseRent());
    }

    @PostMapping("/units/{unitId}/details")
    public void updateDetails(@PathVariable UUID unitId, @RequestBody Map<String, String> details) {
        portfolio.updateUnitDetails(unitId, details);
    }

    @PostMapping("/units/{unitId}/open")
    public void openToRent(@PathVariable UUID unitId, @RequestBody(required = false) ReasonRequest request) {
        portfolio.openUnitToRent(unitId, reasonOf(request));
    }

    @PostMapping("/units/{unitId}/close")
    public void closeToRent(@PathVariable UUID unitId, @RequestBody(required = false) ReasonRequest request) {
        portfolio.closeUnitToRent(unitId, reasonOf(request));
    }

    private static String reasonOf(ReasonRequest request) {
        return request == null || request.reason() == null ? "" : request.reason();
    }
}
