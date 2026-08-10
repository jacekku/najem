package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Identity and ownership of a building. workspaceId is fixed at creation and never changes. */
public class Property {

    private UUID id;
    private UUID workspaceId;
    private String address;
    private List<Owner> owners = List.of();
    private BigDecimal rentTarget;
    /** Latest statutory deadline per type; a re-inspection moves it. */
    private final Map<InspectionType, LocalDate> nextDueByType = new EnumMap<>(InspectionType.class);

    private Property() {
    }

    public static List<Object> create(UUID propertyId, UUID workspaceId, String address,
                                      List<Owner> owners) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("A property needs an address");
        }
        return List.of(new PropertyEvents.PropertyCreated(workspaceId, propertyId, address.strip(),
            stakes(owners)));
    }

    /**
     * A share is a number or it is nothing — checked on <em>every</em> writer of the owner list.
     *
     * <p>{@code required} on the input covers the browser that cooperates; this covers the one that
     * does not — Spring binds an empty {@code share=} to a null element of the RIGHT-SIZED list, so
     * the controller's length check passes and the null reaches {@link #warnings()}, where
     * {@code reduce(ZERO, BigDecimal::add)} throws inside a render.
     *
     * <p>The guard belongs here rather than in the controller because REST reaches the same factory,
     * and because a null stake written to the stream cannot be corrected by any screen. That last
     * argument is what makes it one method called from two places rather than a check on
     * {@code create}: it is equally true of {@link #changeOwnership}, which had no caller yet and so
     * had no guard — a rule that holds only for the routes somebody remembered is refactoring rule
     * 9's procedural invariant. Structural instead: nothing reaches a {@code PropertyCreated} or a
     * {@code PropertyOwnershipChanged} without passing through here.
     */
    private static List<Owner> stakes(List<Owner> owners) {
        if (owners == null) {
            return List.of();
        }
        for (Owner owner : owners) {
            if (owner == null || owner.sharePercent() == null
                || owner.sharePercent().signum() < 0) {
                throw new IllegalArgumentException("An owner needs a share of zero or more");
            }
        }
        return List.copyOf(owners);
    }

    public List<Object> setRentTarget(BigDecimal amount) {
        return List.of(new PropertyEvents.PropertyRentTargetSet(workspaceId, id, amount));
    }

    public List<Object> changeOwnership(List<Owner> newOwners) {
        return List.of(new PropertyEvents.PropertyOwnershipChanged(workspaceId, id,
            stakes(newOwners)));
    }

    public List<Object> updateDetails(Map<String, String> details) {
        return List.of(new PropertyEvents.PropertyDetailsUpdated(workspaceId, id, details));
    }

    /** Soft check: shares should total 100% — confirm, don't block. */
    /**
     * Statutory inspection (art. 62). The next deadline is computed from the type's interval and
     * carried on the event, so the property can answer "what is overdue" without knowing the
     * statute at read time.
     */
    public List<Object> recordInspection(UUID inspectionId, InspectionType type,
                                         LocalDate performedOn, String reportDoc, String findings) {
        if (type == null) {
            throw new IllegalArgumentException("An inspection needs an explicit type");
        }
        return List.of(new PropertyEvents.InspectionCompleted(workspaceId, id, inspectionId, type,
            performedOn, type.nextDue(performedOn), reportDoc, findings));
    }

    /** Empty when this type has never been inspected — absence is not an overdue deadline. */
    public Optional<LocalDate> nextDue(InspectionType type) {
        return Optional.ofNullable(nextDueByType.get(type));
    }

    // TODO: this message reaches a manager in English, on an otherwise Polish screen — the add-
    // property screen flashes it onto the units board after a create. It is not fixable here: the
    // domain has no business holding Polish copy, and the web layer cannot translate it without
    // string-matching the sentence, which is worse than the problem. The fix is a structured
    // warning — a type carrying a kind and its values (SHARES_DO_NOT_TOTAL, actual=90) that each
    // adapter renders in its own words — and it is not local to this method, because every
    // `warnings.add(...)` in Warnings' callers across the module has the same shape and would have
    // to move together. Deliberately left until somebody does all of them at once.
    public Warnings warnings() {
        var warnings = new Warnings();
        var total = owners.stream().map(Owner::sharePercent).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            warnings.add("Ownership shares total " + total.stripTrailingZeros().toPlainString()
                + ", expected 100");
        }
        return warnings;
    }

    /**
     * Refuses a caller who does not own this property. The unit's rule, on the parent — see
     * {@link Unit#requireOwnedBy(UUID)} for why the stream answers this rather than the projection.
     *
     * <p>It also supplies what a child needs: {@code addUnit} must stamp the new unit with its
     * parent's workspace, and asking the parent to confirm the caller owns it is the same load. One
     * read that both checks and answers, rather than a guard query and a rebuild that could differ.
     */
    public void requireOwnedBy(UUID caller) {
        if (caller == null || workspaceId == null || !workspaceId.equals(caller)) {
            throw new UnknownInThisWorkspaceException("property " + id);
        }
    }

    public static Property from(List<Object> events) {
        var property = new Property();
        events.forEach(property::apply);
        return property;
    }

    private void apply(Object event) {
        switch (event) {
            case PropertyEvents.InspectionCompleted e ->
                nextDueByType.put(e.type(), e.nextDueOn());
            case PropertyEvents.PropertyCreated e -> {
                id = e.propertyId();
                workspaceId = e.workspaceId();
                address = e.address();
                owners = e.owners();
            }
            case PropertyEvents.PropertyRentTargetSet e -> rentTarget = e.amount();
            case PropertyEvents.PropertyOwnershipChanged e -> owners = e.owners();
            case PropertyEvents.PropertyDetailsUpdated e -> { }
            default -> throw new UnknownEventException(event);
        }
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public String address() {
        return address;
    }

    public List<Owner> owners() {
        return owners;
    }

    public BigDecimal rentTarget() {
        return rentTarget;
    }
}
