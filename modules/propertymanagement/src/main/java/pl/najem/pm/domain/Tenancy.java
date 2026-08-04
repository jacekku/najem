package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The agreement arc: people, money terms, lifecycle Reserved -> Active -> Ended.
 *
 * <p>Transitions are loosely constrained by design (expert system). Only the lifecycle
 * ordering is enforced; every business rule about amounts and legality warns instead of
 * blocking, so a manager who knows better is never stopped by the software.
 */
public class Tenancy {

    public enum State { RESERVED, ACTIVE, CANCELLED, ENDED }

    private UUID id;
    private UUID workspaceId;
    private UUID unitId;
    private final List<UUID> tenantContactIds = new ArrayList<>();
    private final List<UUID> guarantorContactIds = new ArrayList<>();
    private LocalDate startDate;
    private LocalDate endDate;
    private LegalForm legalForm;
    private MonthlyAmount monthly;
    private int rentDay;
    private BigDecimal depositAmount;
    private String paymentReference;
    private State state;

    private Tenancy() {
    }

    public static List<Object> reserve(ReserveTenancy c) {
        if (c.tenantContactIds().isEmpty()) {
            throw new IllegalArgumentException("A tenancy needs at least one tenant contact");
        }
        return List.of(new TenancyEvents.TenancyReserved(c.workspaceId(), c.tenancyId(), c.unitId(),
            List.copyOf(c.tenantContactIds()), List.copyOf(c.guarantorContactIds()),
            c.startDate(), c.term().endDate(), c.legalForm(), c.monthly(), c.rentDay(),
            c.depositAmount(), c.paymentReference()));
    }

    public List<Object> addTenant(UUID contactId) {
        return List.of(new TenancyEvents.TenantAddedToTenancy(workspaceId, id, contactId));
    }

    public List<Object> removeTenant(UUID contactId) {
        return List.of(new TenancyEvents.TenantRemovedFromTenancy(workspaceId, id, contactId));
    }

    public List<Object> cancelReservation(String reason) {
        if (state != State.RESERVED) {
            throw new IllegalStateException(
                "Only a reserved tenancy can be cancelled (state: " + state + ")");
        }
        return List.of(new TenancyEvents.TenancyReservationCancelled(workspaceId, id, reason));
    }

    public List<Object> activate(LocalDate on) {
        if (state != State.RESERVED) {
            throw new IllegalStateException(
                "Only a reserved tenancy can be activated (state: " + state + ")");
        }
        return List.of(new TenancyEvents.TenancyActivated(workspaceId, id, on));
    }

    /**
     * Soft checks only — the manager confirms; nothing here blocks. The authoritative
     * statutory gate lives in the Tenancy Accounting ACL; these exist to catch the mistake
     * at data entry, where the manager actually is.
     */
    public Warnings warnings() {
        var warnings = new Warnings();
        if (monthly.breakdown() != null
                && monthly.breakdown().sum().compareTo(monthly.total()) != 0) {
            warnings.add("Breakdown totals " + plain(monthly.breakdown().sum())
                + " but monthly total is " + plain(monthly.total()));
        }
        if (depositAmount != null) {
            var cap = monthly.total().multiply(new BigDecimal(legalForm.depositCapMultiplier()));
            if (depositAmount.compareTo(cap) > 0) {
                warnings.add("Deposit " + plain(depositAmount) + " exceeds the statutory "
                    + legalForm.depositCapMultiplier() + "x cap for " + legalForm
                    + " (" + plain(cap) + ")");
            }
        }
        if (endDate != null && startDate.plusYears(10).isBefore(endDate)) {
            warnings.add("Fixed term longer than 10 years");
        }
        return warnings;
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    public static Tenancy from(List<Object> events) {
        var tenancy = new Tenancy();
        events.forEach(tenancy::apply);
        return tenancy;
    }

    private void apply(Object event) {
        switch (event) {
            case TenancyEvents.TenancyReserved e -> {
                id = e.tenancyId();
                workspaceId = e.workspaceId();
                unitId = e.unitId();
                tenantContactIds.addAll(e.tenantContactIds());
                guarantorContactIds.addAll(e.guarantorContactIds());
                startDate = e.startDate();
                endDate = e.endDate();
                legalForm = e.legalForm();
                monthly = e.monthly();
                rentDay = e.rentDay();
                depositAmount = e.depositAmount();
                paymentReference = e.paymentReference();
                state = State.RESERVED;
            }
            case TenancyEvents.TenantAddedToTenancy e -> tenantContactIds.add(e.contactId());
            case TenancyEvents.TenantRemovedFromTenancy e -> tenantContactIds.remove(e.contactId());
            case TenancyEvents.TenancyReservationCancelled e -> state = State.CANCELLED;
            case TenancyEvents.TenancyActivated e -> state = State.ACTIVE;
            default -> throw new IllegalArgumentException("Unknown event: " + event.getClass());
        }
    }

    public State state() {
        return state;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID unitId() {
        return unitId;
    }

    public List<UUID> tenantContactIds() {
        return List.copyOf(tenantContactIds);
    }

    public List<UUID> guarantorContactIds() {
        return List.copyOf(guarantorContactIds);
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public LegalForm legalForm() {
        return legalForm;
    }

    public MonthlyAmount monthly() {
        return monthly;
    }

    public int rentDay() {
        return rentDay;
    }

    public BigDecimal depositAmount() {
        return depositAmount;
    }

    public String paymentReference() {
        return paymentReference;
    }
}
