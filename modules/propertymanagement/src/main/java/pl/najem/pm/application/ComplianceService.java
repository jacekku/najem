package pl.najem.pm.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.InspectionType;
import pl.najem.pm.domain.Property;
import pl.najem.pm.domain.PropertyEvents;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Statutory inspections (art. 62) and what is past its deadline.
 *
 * <p>Takes the acting workspace and checks it against the property rebuilt from its own stream,
 * rather than leaving that to a guard reading pm_property in a controller. Same reasoning as
 * {@link PortfolioService}: one question, asked once, of the record.
 *
 * <p>Its two stores are two ports on purpose — {@link InspectionProjection} to write and
 * {@link OverdueInspectionQuery} to read. Both point at pm_inspection, and keeping them apart is
 * what stops the overdue list being reachable from code that only needs to record.
 */
@Service
@Transactional
public class ComplianceService {

    private final EventStore store;
    private final InspectionProjection projection;
    private final OverdueInspectionQuery overdue;

    public ComplianceService(EventStore store, InspectionProjection projection,
                             OverdueInspectionQuery overdue) {
        this.store = store;
        this.projection = projection;
        this.overdue = overdue;
    }

    /**
     * The id is minted here and put on the event before the append, not beside the insert after it.
     * That is what makes the row reproducible from the stream — see
     * {@link PropertyEvents.InspectionCompleted}.
     *
     * <p>The projection is told what the event says rather than recomputing it. {@code nextDueOn}
     * used to be worked out a second time from {@code type.nextDue(performedOn)} for the row, which
     * applied the statutory interval in two places; if the interval ever changes, the deadline the
     * property recorded and the deadline the screen shows have to be the same one.
     */
    public UUID recordInspection(UUID workspaceId, UUID propertyId, InspectionType type,
                                 LocalDate performedOn, String reportDoc, String findings) {
        var stream = store.load(propertyId, "Property");
        var property = Property.from(stream.events());
        property.requireOwnedBy(workspaceId);

        UUID inspectionId = UUID.randomUUID();
        var events = property.recordInspection(inspectionId, type, performedOn, reportDoc, findings);
        store.append(propertyId, "Property", stream.version(), events, List.of());

        var recorded = (PropertyEvents.InspectionCompleted) events.getFirst();
        projection.inspectionRecorded(inspectionId, workspaceId, propertyId, recorded.type(),
            recorded.performedOn(), recorded.nextDueOn(), recorded.reportDoc(),
            recorded.findings());
        return inspectionId;
    }

    public List<OverdueInspection> overdue(UUID workspaceId, LocalDate on) {
        return overdue.overdue(workspaceId, on);
    }
}
