package pl.najem.fakebank;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * A request this seeder cannot use is the caller's to fix, so it gets a 400 and a message
     * naming the problem.
     *
     * <p>{@link HttpMessageNotReadableException} is included because a validating constructor
     * throws <em>during</em> deserialization: Jackson wraps the failure, and without unwrapping it
     * the caller receives a generic parse error that does not name the field. The reason a caller
     * is left guessing and the reason they see a 500 are the same reason.
     */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String unusableRequest(Exception exception) {
        return rootCause(exception).getMessage();
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
