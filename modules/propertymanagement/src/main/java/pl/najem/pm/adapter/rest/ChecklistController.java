package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.ChecklistService;
import pl.najem.pm.application.WorkspaceGuard;
import pl.najem.pm.domain.ChecklistPhase;
import pl.najem.pm.domain.HandoverProtocol;
import pl.najem.pm.domain.MeterReading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pm/tenancies/{tenancyId}")
public class ChecklistController {

    /** Wire DTOs — external JSON never becomes a domain record directly. */
    public record ChecklistItemRequest(String key, String phase) {}

    public record MeterReadingDto(String meterId, String utility, BigDecimal reading) {}

    public record HandoverRequest(String type, List<MeterReadingDto> meterReadings,
                                  String conditionNotes, List<String> photoRefs,
                                  String signedDocRef, LocalDate date) {}

    private final ChecklistService checklists;
    private final WorkspaceGuard guard;

    public ChecklistController(ChecklistService checklists, WorkspaceGuard guard) {
        this.checklists = checklists;
        this.guard = guard;
    }

    @PostMapping("/checklist")
    public void addItem(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody ChecklistItemRequest request) {
        guard.requireTenancy(workspaceId, tenancyId);
        checklists.addItem(tenancyId, request.key(), phaseOf(request.phase()));
    }

    @PostMapping("/checklist/{key}/complete")
    public void completeItem(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @PathVariable String key) {
        guard.requireTenancy(workspaceId, tenancyId);
        checklists.completeItem(tenancyId, key);
    }

    @PostMapping("/handover")
    public void recordHandover(@RequestHeader(WorkspaceHeader.NAME) UUID workspaceId,
                           @PathVariable UUID tenancyId, @RequestBody HandoverRequest request) {
        var readings = request.meterReadings() == null ? List.<MeterReading>of()
            : request.meterReadings().stream()
                .map(r -> new MeterReading(r.meterId(), r.utility(), r.reading())).toList();
        guard.requireTenancy(workspaceId, tenancyId);
        checklists.recordHandover(tenancyId, new HandoverProtocol(phaseOf(request.type()), readings,
            request.conditionNotes(),
            request.photoRefs() == null ? List.of() : request.photoRefs(),
            request.signedDocRef(), request.date()));
    }

    /**
     * No default. A missing phase on a handover used to mean "move-in", so a move-out protocol
     * sent without one was recorded as the wrong document and never reached Accounting.
     */
    private static ChecklistPhase phaseOf(String wireName) {
        if (wireName == null) {
            throw new IllegalArgumentException("phase is required");
        }
        return ChecklistPhase.valueOf(wireName.toUpperCase().replace('-', '_'));
    }
}
