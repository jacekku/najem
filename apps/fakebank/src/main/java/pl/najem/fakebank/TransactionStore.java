package pl.najem.fakebank;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory statement storage, one list per IBAN. Nothing is persisted; restart clears it. */
@Component
public class TransactionStore {

    private final Map<String, List<BankTransactionDto>> accounts = new ConcurrentHashMap<>();

    public void add(String iban, BankTransactionDto transaction) {
        accounts.computeIfAbsent(iban, key -> new CopyOnWriteArrayList<>()).add(transaction);
    }

    public void addAll(String iban, List<BankTransactionDto> transactions) {
        accounts.computeIfAbsent(iban, key -> new CopyOnWriteArrayList<>()).addAll(transactions);
    }

    /** @param since inclusive lower bound on booking date; null means no bound. */
    public List<BankTransactionDto> find(String iban, LocalDate since) {
        return accounts.getOrDefault(iban, List.of()).stream()
            .filter(transaction -> since == null || !transaction.bookingDate().isBefore(since))
            .toList();
    }
}
