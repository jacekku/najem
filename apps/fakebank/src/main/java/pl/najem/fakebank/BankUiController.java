package pl.najem.fakebank;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import pl.najem.mt940.Mt940Statement;
import pl.najem.mt940.Mt940Writer;

import java.math.BigDecimal;
import java.util.List;

/**
 * A window onto what this fake bank is holding.
 *
 * <p>It renders the same data the API serves, through the same {@link StatementRenderer} the MT940
 * export uses — not a parallel read of {@link TransactionStore}. A screen that computed its own
 * view of the statements could disagree with the file accounting actually imports, and the whole
 * value of looking at this page is that it shows what the other side will receive.
 *
 * <p>Read-only. Seeding stays on {@code POST /api/scenarios}; there is no button here that changes
 * anything, so nothing on this page can put the demo into a state the API did not.
 */
@Controller
public class BankUiController {

    private final TransactionStore store;
    private final StatementRenderer renderer;

    public BankUiController(TransactionStore store, StatementRenderer renderer) {
        this.store = store;
        this.renderer = renderer;
    }

    @GetMapping("/")
    public String accounts(Model model) {
        List<String> ibans = store.ibans();
        model.addAttribute("accounts", ibans.stream().map(this::summarise).toList());
        return "accounts";
    }

    @GetMapping("/accounts/{iban}")
    public String account(@PathVariable String iban, Model model) {
        List<BankTransactionDto> transactions = store.find(iban, null);
        model.addAttribute("iban", iban);
        model.addAttribute("statements", renderer.render(iban, transactions));
        model.addAttribute("empty", transactions.isEmpty());
        return "account";
    }

    /**
     * The MT940 file itself, for the panel on the account page.
     *
     * <p>Serving the real export rather than a re-rendering of it is the point: what is on screen
     * is byte-for-byte what {@code GET /api/accounts/{iban}/statement.mt940} hands to accounting.
     */
    @GetMapping(value = "/accounts/{iban}/mt940", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String mt940(@PathVariable String iban) {
        return Mt940Writer.write(renderer.render(iban, store.find(iban, null)));
    }

    /** What the accounts list shows per row, derived rather than stored. */
    public record AccountSummary(String iban, int lines, String currencies, BigDecimal net) {}

    private AccountSummary summarise(String iban) {
        List<Mt940Statement> statements = renderer.render(iban, store.find(iban, null));
        int lines = statements.stream().mapToInt(statement -> statement.lines().size()).sum();
        String currencies = String.join(", ",
            statements.stream().map(Mt940Statement::currency).toList());
        return new AccountSummary(iban, lines, currencies, net(statements));
    }

    /**
     * Credits less debits, summed across currencies.
     *
     * <p>Summing across currencies is arithmetic nonsense and the template says so — it is a
     * rough "is money going in or out" for a demo, not a balance. FakeBank holds no opening
     * balance, so a real one cannot be computed from what it knows, and inventing an opening
     * figure would put a plausible number from nowhere on the screen.
     */
    private static BigDecimal net(List<Mt940Statement> statements) {
        return statements.stream()
            .flatMap(statement -> statement.lines().stream())
            .map(line -> switch (line.mark()) {
                case C -> line.amount();
                case D -> line.amount().negate();
            })
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
