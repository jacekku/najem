package pl.najem.acc.adapter.rest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.ReconciliationService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/acc")
public class ReconciliationController {

    private final IngestionService ingestion;
    private final ReconciliationService reconciliation;
    private final JdbcTemplate jdbc;

    public ReconciliationController(IngestionService ingestion, ReconciliationService reconciliation,
                                    JdbcTemplate jdbc) {
        this.ingestion = ingestion;
        this.reconciliation = reconciliation;
        this.jdbc = jdbc;
    }

    @PostMapping("/ingest/fetch")
    public void fetch() {
        ingestion.fetchAndIngest();
    }

    @GetMapping("/suggestions")
    public List<Map<String, Object>> suggestions() {
        return jdbc.query("select payment_id, charge_id from acc_suggestion",
            (rs, i) -> Map.of("paymentId", rs.getObject(1), "chargeId", rs.getObject(2)));
    }

    @PostMapping("/payments/{paymentId}/confirm")
    public void confirm(@PathVariable UUID paymentId) {
        reconciliation.confirm(paymentId);
    }
}
