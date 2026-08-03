package pl.najem.acc.adapter.bank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pl.najem.acc.application.BankLine;
import pl.najem.acc.application.BankStatementPort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class FakeBankAdapter implements BankStatementPort {

    /** Wire shape of the FakeBank transactions endpoint. */
    record FakeBankTransaction(String id, BigDecimal amount, String title, LocalDate bookingDate) {}

    private final RestClient client;
    private final String iban;

    public FakeBankAdapter(@Value("${najem.bank.base-url}") String baseUrl,
                           @Value("${najem.bank.iban}") String iban) {
        this.client = RestClient.create(baseUrl);
        this.iban = iban;
    }

    @Override
    public List<BankLine> fetchSince(LocalDate since) {
        List<FakeBankTransaction> transactions = client.get()
            .uri("/api/accounts/{iban}/transactions?since={since}", iban, since)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        if (transactions == null) {
            return List.of();
        }
        return transactions.stream()
            .map(tx -> new BankLine(tx.id(), tx.amount(), tx.title(), tx.bookingDate()))
            .toList();
    }
}
