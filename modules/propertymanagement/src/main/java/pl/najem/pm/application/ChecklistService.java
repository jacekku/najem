package pl.najem.pm.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.MoveOutProtocolRecordedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.HandoverProtocol;
import pl.najem.pm.domain.Tenancy;

import java.util.List;
import java.util.UUID;

/**
 * Pre-activation and end-of-tenancy checklists, plus the typed handover protocols.
 *
 * <p>Every command takes the caller's workspace and asks the tenancy itself whether it is theirs.
 * That was {@code WorkspaceGuard.requireTenancy} in {@code ChecklistController}, asked of
 * pm_tenancy — a projection — while the aggregate holding the same fact was being rebuilt one line
 * later anyway.
 */
@Service
@Transactional
public class ChecklistService {

    private final EventStore store;

    public ChecklistService(EventStore store) {
        this.store = store;
    }

    public void addItem(UUID workspaceId, UUID tenancyId, String key, ChecklistPhase phase) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.addChecklistItem(key, phase), List.of());
    }

    public void completeItem(UUID workspaceId, UUID tenancyId, String key) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.completeChecklistItem(key), List.of());
    }

    /**
     * Only the move-out protocol crosses the boundary. The move-in one is PM's own record of the
     * flat's condition; the move-out one carries the readings Accounting trues media up against
     * and the date that, with the vacate date, fixes their deposit-settlement deadline.
     */
    public void recordHandover(UUID workspaceId, UUID tenancyId, HandoverProtocol protocol) {
        var stream = store.load(tenancyId, "Tenancy");
        var tenancy = Tenancy.from(stream.events());
        tenancy.requireOwnedBy(workspaceId);
        store.append(tenancyId, "Tenancy", stream.version(),
            tenancy.recordHandoverProtocol(protocol),
            protocol.type() != ChecklistPhase.END_OF_TENANCY ? List.of()
                : List.of(new MoveOutProtocolRecordedEvent(tenancy.workspaceId(), tenancyId,
                    protocol.date(), protocol.meterReadings().stream()
                        .map(r -> new pl.najem.contracts.events.MeterReading(
                            r.meterId(), r.utility(), r.reading()))
                        .toList())));
    }
}
