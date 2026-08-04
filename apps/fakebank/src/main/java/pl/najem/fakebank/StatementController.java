package pl.najem.fakebank;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.mt940.Mt940Writer;

import java.time.LocalDate;

/** The same seeded lines the JSON endpoint serves, in the format a bank would hand over. */
@RestController
public class StatementController {

    private final TransactionStore store;
    private final StatementRenderer renderer;

    public StatementController(TransactionStore store, StatementRenderer renderer) {
        this.store = store;
        this.renderer = renderer;
    }

    @GetMapping(value = "/api/accounts/{iban}/statement.mt940", produces = MediaType.TEXT_PLAIN_VALUE)
    public String statement(@PathVariable String iban,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return Mt940Writer.write(renderer.render(iban, store.find(iban, since)));
    }
}
