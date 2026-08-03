package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.PortfolioService;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pm")
public class PortfolioController {

    public record CreatePropertyRequest(String address) {}
    public record AddUnitRequest(String name, BigDecimal baseRent) {}

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @PostMapping("/properties")
    public Map<String, UUID> createProperty(@RequestBody CreatePropertyRequest request) {
        return Map.of("propertyId", portfolio.createProperty(request.address()));
    }

    @PostMapping("/properties/{propertyId}/units")
    public Map<String, UUID> addUnit(@PathVariable UUID propertyId, @RequestBody AddUnitRequest request) {
        return Map.of("unitId", portfolio.addUnit(propertyId, request.name(), request.baseRent()));
    }
}
