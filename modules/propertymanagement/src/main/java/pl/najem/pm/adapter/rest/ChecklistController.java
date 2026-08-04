package pl.najem.pm.adapter.rest;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.ChecklistService;
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

    public ChecklistController(ChecklistService checklists) {
        this.checklists = checklists;
    }

    @PostMapping("/checklist")
    public void addItem(@PathVariable UUID tenancyId, @RequestBody ChecklistItemRequest request) {
        checklists.addItem(tenancyId, request.key(), phaseOf(request.phase()));
    }

    @PostMapping("/checklist/{key}/complete")
    public void completeItem(@PathVariable UUID tenancyId, @PathVariable String key) {
        checklists.completeItem(tenancyId, key);
    }

    @PostMapping("/handover")
    public void recordHandover(@PathVariable UUID tenancyId, @RequestBody HandoverRequest request) {
        var readings = request.meterReadings() == null ? List.<MeterReading>of()
            : request.meterReadings().stream()
                .map(r -> new MeterReading(r.meterId(), r.utility(), r.reading())).toList();
        checklists.recordHandover(tenancyId, new HandoverProtocol(phaseOf(request.type()), readings,
            request.conditionNotes(),
            request.photoRefs() == null ? List.of() : request.photoRefs(),
            request.signedDocRef(), request.date()));
    }

    private static ChecklistPhase phaseOf(String wireName) {
        return wireName == null ? ChecklistPhase.PRE_ACTIVATION
            : ChecklistPhase.valueOf(wireName.toUpperCase().replace('-', '_'));
    }
}
