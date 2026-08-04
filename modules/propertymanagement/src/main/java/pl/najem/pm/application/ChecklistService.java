package pl.najem.pm.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.HandoverProtocol;
import pl.najem.pm.domain.Tenancy;

import java.util.List;
import java.util.UUID;

/** Pre-activation and end-of-tenancy checklists, plus the typed handover protocols. */
@Service
@Transactional
public class ChecklistService {

    private final EventStore store;

    public ChecklistService(EventStore store) {
        this.store = store;
    }

    public void addItem(UUID tenancyId, String key, ChecklistPhase phase) {
        var stream = store.load(tenancyId);
        store.append(tenancyId, "Tenancy", stream.version(),
            Tenancy.from(stream.events()).addChecklistItem(key, phase), List.of());
    }

    public void completeItem(UUID tenancyId, String key) {
        var stream = store.load(tenancyId);
        store.append(tenancyId, "Tenancy", stream.version(),
            Tenancy.from(stream.events()).completeChecklistItem(key), List.of());
    }

    public void recordHandover(UUID tenancyId, HandoverProtocol protocol) {
        var stream = store.load(tenancyId);
        store.append(tenancyId, "Tenancy", stream.version(),
            Tenancy.from(stream.events()).recordHandoverProtocol(protocol), List.of());
    }
}
