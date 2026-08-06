package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.ReconciliationService;
import pl.najem.acc.application.SuggestionQuery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

@RestController
@RequestMapping("/api/acc")
public class ReconciliationController {

    private final IngestionService ingestion;
    private final ReconciliationService reconciliation;
    private final SuggestionQuery suggestions;

    public ReconciliationController(IngestionService ingestion, ReconciliationService reconciliation,
                                    SuggestionQuery suggestions) {
        this.ingestion = ingestion;
        this.reconciliation = reconciliation;
        this.suggestions = suggestions;
    }

    /**
     * The workspace header is required on every mapping here, read and write alike.
     *
     * <p>It used to be optional on reads, and the asymmetry was mine: I argued that a read with no
     * header shows the wrong (empty) data and the caller notices, while a write with no header puts
     * real data into books nobody named. That was wrong about what the reads returned — a missing
     * header did not produce empty data, it produced workspace {@code …0001}'s data, which is one
     * agency's board and suspense queue handed to a caller who identified nothing.
     *
     * <p>The header itself is a <strong>stand-in until the workspace is taken from the verified token
     * and checked against the caller's memberships</strong>. Requiring it stops a caller omitting the
     * workspace; nothing here stops a caller naming someone else's. That check does not exist yet,
     * and this annotation must not be read as though it did.
     */
    @PostMapping("/ingest/fetch")
    public void fetch(@ActingWorkspace UUID workspaceId) {
        ingestion.fetchAndIngest(workspaceId);
    }

    /**
     * The suggestions awaiting a decision, each with the evidence its tier is asserting.
     *
     * <p>This used to return two identifiers and nothing else, so confirming a match meant
     * confirming an opaque pair of UUIDs. The tier is the point of the ladder — a tier-1 certainty
     * and a tier-4 guess are not worth the same confidence — but a tier with nothing to check it
     * against is just a number, so the amounts, dates and references come with it.
     *
     * <p>{@code isPartPayment} is computed here rather than left to the caller: tiers 2 and above do
     * not constrain the amount, so a suggestion may settle only part of the charge, and that has to
     * be visible before the click.
     */
    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions(
        @ActingWorkspace UUID workspaceId) {
        return suggestions.forWorkspace(workspaceId).stream().map(row -> {
            // LinkedHashMap rather than Map.of: fourteen entries exceeds its overloads, and the
            // field order is what a human reads when they curl this.
            var wire = new LinkedHashMap<String, Object>();
            wire.put("paymentId", row.paymentId());
            wire.put("chargeId", row.chargeId());
            wire.put("tenancyId", row.tenancyId());
            wire.put("tier", row.tier());
            wire.put("paidAmount", row.paidAmount());
            wire.put("chargedAmount", row.chargedAmount());
            wire.put("outstanding", row.outstanding());
            wire.put("isPartPayment", row.isPartPayment());
            wire.put("paidOn", row.paidOn());
            wire.put("dueDate", row.dueDate());
            wire.put("component", row.component());
            wire.put("quotedReference", row.quotedReference());
            wire.put("expectedReference", row.expectedReference());
            wire.put("payerName", row.payerName());
            wire.put("payerIban", row.payerIban());
            return (Map<String, Object>) wire;
        }).toList();
    }

    @PostMapping("/payments/{paymentId}/confirm")
    public void confirm(@PathVariable UUID paymentId,
                        @ActingWorkspace UUID workspaceId) {
        reconciliation.confirm(workspaceId, paymentId);
    }
}
