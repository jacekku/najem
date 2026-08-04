package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Event-sourced aggregate; skeleton lifecycle: RESERVED -> ACTIVE. */
public class Tenancy {

    public enum State { RESERVED, ACTIVE, CANCELLED }

    private UUID id;
    private UUID workspaceId;
    private UUID unitId;
    private LocalDate endDate;
    private LocalDate startDate;
    private BigDecimal monthlyRent;
    private String paymentReference;
    private State state;

    private Tenancy() {
    }

    public static List<Object> reserve(UUID tenancyId, UUID workspaceId, UUID unitId,
                                       LocalDate startDate, LocalDate endDate,
                                       BigDecimal monthlyRent, String paymentReference) {
        return List.of(new TenancyReserved(workspaceId, tenancyId, unitId, startDate, endDate,
            monthlyRent, paymentReference));
    }

    public List<Object> cancelReservation(String reason) {
        if (state != State.RESERVED) {
            throw new IllegalStateException(
                "Only a reserved tenancy can be cancelled (state: " + state + ")");
        }
        return List.of(new TenancyReservationCancelled(workspaceId, id, reason));
    }

    public List<Object> activate(LocalDate on) {
        if (state != State.RESERVED) {
            throw new IllegalStateException("Only a reserved tenancy can be activated (state: " + state + ")");
        }
        return List.of(new TenancyActivated(id, on));
    }

    public static Tenancy from(List<Object> events) {
        Tenancy tenancy = new Tenancy();
        for (Object event : events) {
            tenancy.apply(event);
        }
        return tenancy;
    }

    private void apply(Object event) {
        switch (event) {
            case TenancyReserved e -> {
                id = e.tenancyId();
                workspaceId = e.workspaceId();
                unitId = e.unitId();
                endDate = e.endDate();
                startDate = e.startDate();
                monthlyRent = e.monthlyRent();
                paymentReference = e.paymentReference();
                state = State.RESERVED;
            }
            case TenancyActivated e -> state = State.ACTIVE;
            case TenancyReservationCancelled e -> state = State.CANCELLED;
            default -> throw new IllegalArgumentException("Unknown event: " + event.getClass());
        }
    }

    public State state() {
        return state;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public UUID unitId() {
        return unitId;
    }

    public LocalDate startDate() {
        return startDate;
    }

    public BigDecimal monthlyRent() {
        return monthlyRent;
    }

    public String paymentReference() {
        return paymentReference;
    }
}
