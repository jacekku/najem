package pl.najem.acc.adapter.bank;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import pl.najem.acc.application.BankLine;
import pl.najem.acc.application.BankStatementPort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A stand-in bank. <strong>Not a {@code @Component}</strong> — the composition root decides whether
 * a deployment gets one, gated on {@code najem.bank.fake.enabled} with no default.
 *
 * <p>This was an unconditional component, the only implementation of the port, with a packaged
 * {@code base-url} of {@code localhost:8081}. A real deployment therefore started cleanly and
 * reconciled nothing: every fetch returned an empty statement because nothing was listening, which
 * is indistinguishable from a bank with no new transactions. A failure to start is loud, immediate
 * and names the property; a bank that silently reports no money is none of those, and the books it
 * leaves behind look merely quiet.
 *
 * <p>The gate lives in {@code PlatformConfig} rather than here because expressing it as an
 * annotation would put {@code spring-boot-autoconfigure} on this module's compile classpath — a
 * domain module taking on Boot's wiring machinery to answer a question about deployments. Which
 * adapters exist is the composition root's question, and it already answers it for the clock and
 * the event store.
 */
public class FakeBankAdapter implements BankStatementPort {

    /**
     * Wire shape of the FakeBank transactions endpoint. The last six are omitted from the JSON when
     * the bank has nothing to say, so they arrive null and stay null — they are not defaults.
     */
    record FakeBankTransaction(String id, BigDecimal amount, String title, LocalDate bookingDate,
                               String counterpartyName, String counterpartyIban, String bankReference,
                               LocalDate valueDate, String creditDebitIndicator, String currency) {}

    private final RestClient client;

    public FakeBankAdapter(String baseUrl) {
        this.client = RestClient.create(baseUrl);
    }

    @Override
    public List<BankLine> fetchSince(LocalDate since, String iban) {
        List<FakeBankTransaction> transactions = client.get()
            .uri("/api/accounts/{iban}/transactions?since={since}", iban, since)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        if (transactions == null) {
            return List.of();
        }
        return transactions.stream()
            .map(tx -> new BankLine(tx.id(), tx.amount(), tx.title(), tx.bookingDate(),
                tx.counterpartyName(), tx.counterpartyIban(), tx.bankReference(), tx.valueDate(),
                tx.creditDebitIndicator(), tx.currency()))
            .toList();
    }
}
