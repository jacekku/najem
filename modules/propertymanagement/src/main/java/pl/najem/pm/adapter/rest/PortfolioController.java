package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.adapter.rest.PortfolioWire.AddUnitRequest;
import pl.najem.pm.adapter.rest.PortfolioWire.BaseRentRequest;
import pl.najem.pm.adapter.rest.PortfolioWire.CreatePropertyRequest;
import pl.najem.pm.adapter.rest.PortfolioWire.PropertyCreated;
import pl.najem.pm.adapter.rest.PortfolioWire.ReasonRequest;
import pl.najem.pm.adapter.rest.PortfolioWire.UnitAdded;
import pl.najem.pm.adapter.rest.PortfolioWire.UnitDetailsRequest;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

/**
 * Properties and units over HTTP. The shapes it speaks in are {@link PortfolioWire}.
 *
 * <p>No {@code WorkspaceGuard}: every handler hands the acting workspace to the service as the
 * first argument, and the service checks it against the aggregate rebuilt from its own stream. The
 * check did not disappear, it moved to where the decision is and where callers that never reach a
 * controller also pass.
 */
@RestController
@RequestMapping("/api/pm")
public class PortfolioController {

    private final PortfolioService portfolio;

    public PortfolioController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @PostMapping("/properties")
    public PropertyCreated createProperty(
            @ActingWorkspace UUID workspaceId,
            @RequestBody CreatePropertyRequest request) {
        List<Owner> owners = request.owners() == null ? List.of()
            : request.owners().stream().map(o -> new Owner(o.contactId(), o.sharePercent())).toList();
        return new PropertyCreated(portfolio.createProperty(
            workspaceId, request.address(), owners));
    }

    @PostMapping("/properties/{propertyId}/units")
    public UnitAdded addUnit(@ActingWorkspace UUID workspaceId,
                             @PathVariable UUID propertyId, @RequestBody AddUnitRequest request) {
        return new UnitAdded(
            portfolio.addUnit(workspaceId, propertyId, request.name(), request.baseRent()));
    }

    @PostMapping("/units/{unitId}/base-rent")
    public void setBaseRent(@ActingWorkspace UUID workspaceId,
                           @PathVariable UUID unitId, @RequestBody BaseRentRequest request) {
        portfolio.setUnitBaseRent(workspaceId, unitId, request.baseRent());
    }

    @PostMapping("/units/{unitId}/details")
    public void updateDetails(@ActingWorkspace UUID workspaceId,
                              @PathVariable UUID unitId,
                              @RequestBody UnitDetailsRequest request) {
        portfolio.updateUnitDetails(workspaceId, unitId, new HashMap<>(request.details()));
    }

    @PostMapping("/units/{unitId}/open")
    public void openToRent(@ActingWorkspace UUID workspaceId,
                           @PathVariable UUID unitId, @RequestBody(required = false) ReasonRequest request) {
        portfolio.openUnitToRent(workspaceId, unitId, reasonOf(request));
    }

    @PostMapping("/units/{unitId}/close")
    public void closeToRent(@ActingWorkspace UUID workspaceId,
                           @PathVariable UUID unitId, @RequestBody(required = false) ReasonRequest request) {
        portfolio.closeUnitToRent(workspaceId, unitId, reasonOf(request));
    }

    @PostMapping("/units/{unitId}/remove")
    public void removeUnit(@ActingWorkspace UUID workspaceId,
                           @PathVariable UUID unitId,
                           @RequestBody(required = false) ReasonRequest request) {
        portfolio.removeUnit(workspaceId, unitId, request == null ? "" : request.reason());
    }

    private static String reasonOf(ReasonRequest request) {
        return request == null || request.reason() == null ? "" : request.reason();
    }
}
