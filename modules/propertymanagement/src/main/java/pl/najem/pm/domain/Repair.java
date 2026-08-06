package pl.najem.pm.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Something broken on a property or a unit.
 *
 * <p>Deliberately attached to the asset rather than to the tenancy: a leaking tap outlives the
 * tenant who reported it, and the flat's repair history is what a manager actually wants to read.
 * {@code causedByTenancy} records who caused it when that is known, which is a different question
 * from who owns it.
 *
 * <p>Repairs are NOT published to Accounting in the MVP (domain model §2 item 16) — recharging a
 * repair to a tenant is a manual decision, so there is no integration event here.
 */
public class Repair {

    private UUID id;
    private UUID workspaceId;
    private RepairScope scope;
    private UUID assetId;
    private String description;
    private UUID causedByTenancy;
    private StatutoryDutyHint statutoryDutyHint;
    private LocalDate reportedOn;
    private LocalDate completedOn;

    private Repair() {
    }

    public static List<Object> report(UUID repairId, UUID workspaceId, RepairScope scope,
                                      UUID assetId, String description, UUID causedByTenancy,
                                      StatutoryDutyHint hint, LocalDate reportedOn) {
        if (scope == null) {
            throw new IllegalArgumentException("A repair needs an explicit scope");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("A repair needs the asset it is attached to");
        }
        // Rule 7: the hint is what a manager bills from, so a default would pick sides in an
        // art. 6a/6b dispute on their behalf and look like their own conclusion.
        if (hint == null) {
            throw new IllegalArgumentException("A repair needs an explicit statutory duty hint");
        }
        return List.of(new RepairEvents.RepairReported(workspaceId, repairId, scope, assetId,
            description, causedByTenancy, hint, reportedOn));
    }

    public List<Object> complete(LocalDate on, String notes) {
        if (completedOn != null) {
            throw new IllegalStateException(
                "Repair " + id + " was already completed on " + completedOn);
        }
        return List.of(new RepairEvents.RepairCompleted(workspaceId, id, on, notes));
    }

    public static Repair from(List<Object> events) {
        var repair = new Repair();
        events.forEach(repair::apply);
        return repair;
    }

    private void apply(Object event) {
        switch (event) {
            case RepairEvents.RepairReported e -> {
                id = e.repairId();
                workspaceId = e.workspaceId();
                scope = e.scope();
                assetId = e.assetId();
                description = e.description();
                causedByTenancy = e.causedByTenancy();
                statutoryDutyHint = e.statutoryDutyHint();
                reportedOn = e.reportedOn();
            }
            case RepairEvents.RepairCompleted e -> completedOn = e.completedOn();
            default -> throw new IllegalArgumentException("Unknown event: " + event.getClass());
        }
    }

    public boolean isOpen() {
        return completedOn == null;
    }

    /**
     * Refuses a caller who does not own this repair — asked of the repair rebuilt from its own
     * stream, as {@code Unit} and {@code Property} answer the same question.
     *
     * <p>A repair's workspace is inherited from the asset at the moment it was reported and never
     * moves, so this is the same answer the asset would give, one load closer to the decision.
     */
    public void requireOwnedBy(UUID caller) {
        if (caller == null || workspaceId == null || !workspaceId.equals(caller)) {
            throw new UnknownInThisWorkspaceException("repair " + id);
        }
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public RepairScope scope() {
        return scope;
    }

    public UUID assetId() {
        return assetId;
    }

    public String description() {
        return description;
    }

    public UUID causedByTenancy() {
        return causedByTenancy;
    }

    public StatutoryDutyHint statutoryDutyHint() {
        return statutoryDutyHint;
    }

    public LocalDate reportedOn() {
        return reportedOn;
    }

    public LocalDate completedOn() {
        return completedOn;
    }
}
