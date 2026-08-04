package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementRendererTest {

    private final StatementRenderer renderer = new StatementRenderer();
    private final ScenarioCatalog catalog = new ScenarioCatalog();

    private List<BankTransactionDto> seed(String name) {
        return catalog.generate(new ScenarioRequest(
            name, "PL61", "NAJEM/M1/2026", new BigDecimal("2500.00"), LocalDate.of(2026, 9, 1)));
    }

    @Test
    void rendersACreditScenarioAsOneStatement() {
        List<Mt940Statement> statements = renderer.render("PL61", seed("on-time"));

        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst().account()).isEqualTo("PL61");
        assertThat(statements.getFirst().currency()).isEqualTo("PLN");
        assertThat(statements.getFirst().lines()).hasSize(1);
    }

    @Test
    void carriesEveryFieldTheJsonShapeCarries() {
        var line = renderer.render("PL61", seed("on-time")).getFirst().lines().getFirst();
        var dto = seed("on-time").getFirst();

        assertThat(line.amount()).isEqualByComparingTo(dto.amount());
        assertThat(line.bookingDate()).isEqualTo(dto.bookingDate());
        assertThat(line.valueDate()).isEqualTo(dto.valueDate());
        assertThat(line.remittanceInfo()).isEqualTo(dto.title());
        assertThat(line.counterpartyName()).isEqualTo(dto.counterpartyName());
        assertThat(line.counterpartyIban()).isEqualTo(dto.counterpartyIban());
        assertThat(line.bankReference()).isEqualTo(dto.bankReference());
        assertThat(line.mark()).isEqualTo(Mt940Mark.C);
    }

    @Test
    void rendersADebitScenarioWithADebitMarkAndAPositiveAmount() {
        var line = renderer.render("PL61", seed("outgoing-debit")).getFirst().lines().getFirst();

        assertThat(line.mark()).isEqualTo(Mt940Mark.D);
        assertThat(line.amount()).isEqualByComparingTo("287.43");
    }

    @Test
    void splitsCurrenciesIntoSeparateStatementsBecauseMt940StatesCurrencyOnce() {
        List<BankTransactionDto> mixed = new ArrayList<>(seed("on-time"));
        mixed.addAll(seed("foreign-currency"));

        List<Mt940Statement> statements = renderer.render("PL61", mixed);

        assertThat(statements).hasSize(2);
        // First appearance, not alphabet: the PLN scenario is seeded first, so PLN is statement 1
        // even though EUR sorts before it. Ordering by name would mean a later EUR transaction
        // took the number PLN already had -- see aNewCurrencyDoesNotRenumberTheStatementsThatCameBefore.
        assertThat(statements).extracting(Mt940Statement::currency).containsExactly("PLN", "EUR");
        assertThat(statements).extracting(Mt940Statement::statementNumber).containsExactly("1/1", "2/1");
    }

    @Test
    void rendersAnEmptyAccountAsNoStatementsAtAll() {
        assertThat(renderer.render("PL61", List.of())).isEmpty();
    }

    @Test
    void keepsTheTwoLinesOfADuplicateApart() {
        var lines = renderer.render("PL61", seed("duplicate")).getFirst().lines();

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).bankReference()).isNotEqualTo(lines.get(1).bankReference());
    }

    /**
     * Adding a transaction in a new currency must not renumber the statements that already exist.
     *
     * <p>Accounting builds its deduplication key from {@code statement.statementNumber()}, so a
     * statement that changes number re-keys every payment on it: the same transfers are ingested a
     * second time as new payments, and per-workspace uniqueness on that key means they collide with
     * nothing and nobody is told. Alphabetical ordering made this reachable — a EUR line added to a
     * PLN account took the number PLN had — and the booking form's currency field is what put it in
     * a person's hands.
     *
     * <p>First appearance, not alphabet: appending can only ever add a number at the end.
     */
    @Test
    void aNewCurrencyDoesNotRenumberTheStatementsThatCameBefore() {
        List<BankTransactionDto> pln = List.of(
            new BankTransactionDto("a", new BigDecimal("2500.00"), "NAJEM", LocalDate.of(2026, 9, 10),
                null, null, "BNP1", LocalDate.of(2026, 9, 10), "CRDT", "PLN"));

        String plnNumberBefore = renderer.render("PL61", pln).getFirst().statementNumber();

        List<BankTransactionDto> plusEur = new java.util.ArrayList<>(pln);
        plusEur.add(new BankTransactionDto("b", new BigDecimal("600.00"), "RENT", LocalDate.of(2026, 9, 11),
            null, null, "BNP2", LocalDate.of(2026, 9, 11), "CRDT", "EUR"));

        Mt940Statement plnAfter = renderer.render("PL61", plusEur).stream()
            .filter(statement -> statement.currency().equals("PLN")).findFirst().orElseThrow();

        assertThat(plnAfter.statementNumber())
            .as("PLN keeps its number, so accounting's external_id for those lines is unchanged")
            .isEqualTo(plnNumberBefore);
    }
}
