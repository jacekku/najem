package pl.najem.fakebank;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/accounts/{iban}/transactions")
public class AccountsController {

    private final Map<String, List<BankTransactionDto>> accounts = new ConcurrentHashMap<>();

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void seed(@PathVariable String iban, @RequestBody BankTransactionDto transaction) {
        accounts.computeIfAbsent(iban, k -> new CopyOnWriteArrayList<>()).add(transaction);
    }

    @GetMapping
    public List<BankTransactionDto> list(@PathVariable String iban,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return accounts.getOrDefault(iban, List.of()).stream()
            .filter(tx -> since == null || !tx.bookingDate().isBefore(since))
            .toList();
    }
}
