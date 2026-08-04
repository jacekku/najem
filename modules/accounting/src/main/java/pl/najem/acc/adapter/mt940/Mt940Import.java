package pl.najem.acc.adapter.mt940;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.application.BankLine;
import pl.najem.acc.application.IngestionService;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Reader;
import pl.najem.mt940.Mt940Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ingests an uploaded MT940 statement.
 *
 * <p>An upload is <em>push</em>: the file contains what it contains. So it does not go through
 * {@link pl.najem.acc.application.BankStatementPort}, which asks the bank what happened since a
 * date an upload has no way to honour. Both paths meet at {@link IngestionService#ingest}, which is
 * the real ingestion contract — the port is only the polling driver's half.
 */
@Component
public class Mt940Import {

    /**
     * Namespace for ids derived from an uploaded statement.
     *
     * <p>Required, not decorative: {@code acc_payment.external_id} is unique per workspace across
     * every source, so an unprefixed statement/index pair could collide with an id minted by the
     * bank API adapter.
     */
    private static final String SOURCE = "mt940";

    private final IngestionService ingestion;

    public Mt940Import(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Parses the whole text before ingesting anything, so an unreadable statement is refused
     * entirely rather than half-imported. A partial import is worse than a rejected one: it leaves
     * a manager reconciling against a file they believe they uploaded.
     *
     * <p>Parsing first only covers <em>parse</em> failures. {@code @Transactional} covers the rest:
     * {@link IngestionService} propagates {@code REQUIRED}, so every {@code ingest} joins this
     * transaction rather than committing on its own, and a line that fails on the twentieth row
     * rolls back the nineteen before it. Without the annotation each line commits independently
     * and a mid-list failure leaves a partial statement behind.
     *
     * @return how many lines were read from the statement — not how many were new, since re-uploading
     *         a file is an ordinary operator action and its already-known lines are silently skipped
     */
    @Transactional
    public int importStatement(UUID workspaceId, String text) {
        List<BankLine> lines = toBankLines(text);
        for (BankLine line : lines) {
            ingestion.ingest(workspaceId, line);
        }
        return lines.size();
    }

    static List<BankLine> toBankLines(String text) {
        List<BankLine> lines = new ArrayList<>();
        for (Mt940Statement statement : Mt940Reader.read(text)) {
            int index = 0;
            for (Mt940Line line : statement.lines()) {
                lines.add(toBankLine(statement, line, index++));
            }
        }
        return lines;
    }

    private static BankLine toBankLine(Mt940Statement statement, Mt940Line line, int index) {
        return new BankLine(
            externalId(statement, index),
            line.amount(),
            line.remittanceInfo(),
            line.bookingDate(),
            line.counterpartyName(),
            line.counterpartyIban(),
            line.bankReference(),
            line.valueDate(),
            line.mark() == Mt940Mark.D ? BankLine.DEBIT : BankLine.CREDIT,
            statement.currency());
    }

    /**
     * Stable under re-upload — the same file yields the same ids, so ingestion's own deduplication
     * refuses the second import without needing to compare contents. Distinct within a statement,
     * so two genuinely identical transfers on one day stay two payments rather than collapsing into
     * one. Deliberately independent of the bank's own reference, which some banks fill with
     * {@code NONREF} on every line of a statement.
     */
    private static String externalId(Mt940Statement statement, int index) {
        return SOURCE + "/" + statement.account() + "/" + statement.statementNumber() + "/" + index;
    }
}
