package pl.najem.fakebank;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pl.najem.mt940.Mt940Statement;
import pl.najem.mt940.Mt940Writer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A window onto what this fake bank is holding, and — since it is a bank people demonstrate with —
 * a way to put things into it by hand.
 *
 * <p>It renders through the same {@link StatementRenderer} the MT940 export uses, not a second read
 * of {@link TransactionStore}. A screen with its own view of the statements could disagree with the
 * file accounting actually imports, and seeing what the other side receives is the only reason to
 * look at this page.
 *
 * <p><strong>Every write is POST-redirect-GET.</strong> A GET never changes anything, so a refresh
 * or a back button cannot book a second transfer — which for a bank is the difference between a
 * demonstration and a mess. This replaces an earlier invariant that the screens carried no form at
 * all; that was the right rule while the pages were only a view, and it was overruled deliberately
 * when they became a way to build up state by hand.
 */
@Controller
public class BankUiController {

    private final TransactionStore store;
    private final StatementRenderer renderer;
    private final AccountRegistry accounts;

    public BankUiController(TransactionStore store, StatementRenderer renderer, AccountRegistry accounts) {
        this.store = store;
        this.renderer = renderer;
        this.accounts = accounts;
    }

    @GetMapping("/")
    public String accounts(Model model) {
        model.addAttribute("accounts", knownIbans().stream().map(this::summarise).toList());
        model.addAttribute("today", LocalDate.now());
        return "accounts";
    }

    /**
     * Registered accounts and accounts that only exist because something was seeded into them.
     * Both are real to a viewer; only the first can carry an opening balance.
     */
    private List<String> knownIbans() {
        Set<String> all = new LinkedHashSet<>(accounts.ibans());
        all.addAll(store.ibans());
        return all.stream().sorted().toList();
    }

    @PostMapping("/accounts")
    public String openAccount(@RequestParam String iban,
                              @RequestParam(required = false) String holder,
                              @RequestParam String currency,
                              @RequestParam(required = false) BigDecimal openingBalance,
                              RedirectAttributes flash) {
        BankAccount account = accounts.open(new BankAccount(iban, holder, currency, openingBalance));
        flash.addFlashAttribute("opened", account.iban());
        return "redirect:/accounts/" + account.iban();
    }

    @GetMapping("/accounts/{iban}")
    public String account(@PathVariable String iban, Model model) {
        List<BankTransactionDto> transactions = store.find(iban, null);
        model.addAttribute("iban", iban);
        model.addAttribute("account", accounts.find(iban).orElse(null));
        model.addAttribute("statements", statementsFor(iban, transactions));
        model.addAttribute("empty", transactions.isEmpty());
        model.addAttribute("today", LocalDate.now());
        return "account";
    }

    /**
     * Books one transfer by hand.
     *
     * <p>The id is minted here rather than asked for: it is the bank's own reference, a payer does
     * not choose it, and accounting's dedup key is built from it — letting a person type one would
     * make it possible to book the same transfer twice on purpose and hard to do so by accident,
     * which is exactly backwards.
     */
    @PostMapping("/accounts/{iban}/transactions")
    public String book(@PathVariable String iban,
                       @RequestParam BigDecimal amount,
                       @RequestParam String creditDebitIndicator,
                       @RequestParam(required = false) String title,
                       @RequestParam LocalDate bookingDate,
                       @RequestParam(required = false) LocalDate valueDate,
                       @RequestParam(required = false) String counterpartyName,
                       @RequestParam(required = false) String counterpartyIban,
                       @RequestParam(required = false) String currency,
                       RedirectAttributes flash) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                "Amount must be positive — direction is the credit/debit indicator, never a sign");
        }
        String id = "reczna/" + iban + "/" + (store.find(iban, null).size() + 1);
        store.add(iban, new BankTransactionDto(id, amount, blankToNull(title), bookingDate,
            blankToNull(counterpartyName), blankToNull(counterpartyIban), bankReference(id),
            valueDate == null ? bookingDate : valueDate, creditDebitIndicator,
            currencyFor(iban, currency)));
        flash.addFlashAttribute("booked", id);
        return "redirect:/accounts/" + iban;
    }

    /** The raw file, byte-for-byte what {@code GET /api/accounts/{iban}/statement.mt940} serves. */
    @GetMapping(value = "/accounts/{iban}/mt940", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public String mt940(@PathVariable String iban) {
        return Mt940Writer.write(statementsFor(iban, store.find(iban, null)));
    }

    /** A rejected form is the operator's mistake to fix, so it says what was wrong. */
    @ExceptionHandler(IllegalArgumentException.class)
    public String refused(IllegalArgumentException exception, Model model) {
        model.addAttribute("problem", exception.getMessage());
        model.addAttribute("accounts", knownIbans().stream().map(this::summarise).toList());
        model.addAttribute("today", LocalDate.now());
        return "accounts";
    }

    private List<Mt940Statement> statementsFor(String iban, List<BankTransactionDto> transactions) {
        return renderer.render(iban, transactions, currency -> accounts.openingBalanceFor(iban, currency));
    }

    /** What the accounts list shows per row, derived rather than stored. */
    public record AccountSummary(String iban, String holder, int lines, String currencies,
                                 BigDecimal opening, BigDecimal closing, boolean registered) {}

    private AccountSummary summarise(String iban) {
        List<Mt940Statement> statements = statementsFor(iban, store.find(iban, null));
        int lines = statements.stream().mapToInt(statement -> statement.lines().size()).sum();
        String currencies = String.join(", ", statements.stream().map(Mt940Statement::currency).toList());
        BankAccount account = accounts.find(iban).orElse(null);
        return new AccountSummary(iban,
            account == null ? null : account.holder(),
            lines,
            currencies.isEmpty() && account != null ? account.currency() : currencies,
            account == null ? BigDecimal.ZERO : account.openingBalance(),
            closing(statements),
            account != null);
    }

    /**
     * Opening balances plus movements, summed across currencies.
     *
     * <p>Summing across currencies is arithmetic nonsense and the page says so. It is a rough
     * direction-of-travel figure for a demonstration; for the ordinary case of an account holding
     * one currency it is simply the balance, which is why it is worth showing at all.
     */
    private static BigDecimal closing(List<Mt940Statement> statements) {
        BigDecimal closing = BigDecimal.ZERO;
        for (Mt940Statement statement : statements) {
            closing = closing.add(statement.openingBalance());
            for (var line : statement.lines()) {
                closing = switch (line.mark()) {
                    case C -> closing.add(line.amount());
                    case D -> closing.subtract(line.amount());
                };
            }
        }
        return closing;
    }

    /** A bank reference the bank invented, distinct from anything the payer wrote. */
    private static String bankReference(String id) {
        return "RECZ" + Math.abs(id.hashCode() % 100000000);
    }

    /** A currency the account was opened in beats an unstated one; PLN is the last resort. */
    private String currencyFor(String iban, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim().toUpperCase();
        }
        return accounts.find(iban).map(BankAccount::currency).orElse("PLN");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
