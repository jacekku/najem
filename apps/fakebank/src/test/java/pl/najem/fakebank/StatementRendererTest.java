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
        assertThat(statements).extracting(Mt940Statement::currency).containsExactly("EUR", "PLN");
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
}
