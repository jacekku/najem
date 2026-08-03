package pl.najem.fakebank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioCatalog catalog;
    private final TransactionStore store;

    public ScenarioController(ScenarioCatalog catalog, TransactionStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResult seed(@RequestBody ScenarioRequest request) {
        List<BankTransactionDto> transactions = catalog.generate(request);
        store.addAll(request.iban(), transactions);
        return new ScenarioResult(transactions.size(),
            transactions.stream().map(BankTransactionDto::id).toList());
    }

    @ExceptionHandler(UnknownScenarioException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String unknownScenario(UnknownScenarioException exception) {
        return exception.getMessage();
    }
}
