package pl.najem.acc.adapter.mt940;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.application.BankLine;
import pl.najem.acc.application.IngestionService;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Reader;
import pl.najem.mt940.Mt940Statement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

    /**
     * What a bank sends when it has no reference to give. It is a literal meaning "none", not an
     * identifier, so a statement whose every line says {@code NONREF} has effectively supplied
     * nothing — which is why it must not be used as one.
     */
    private static final String NO_REFERENCE = "NONREF";

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
            // Counts how many indistinguishable lines have already been seen in this statement, so
            // the fallback id can tell two genuinely identical transfers apart without depending on
            // where either of them sits in the file.
            var occurrences = new HashMap<String, Integer>();
            for (Mt940Line line : statement.lines()) {
                lines.add(toBankLine(statement, line, occurrences));
            }
        }
        return lines;
    }

    private static BankLine toBankLine(Mt940Statement statement, Mt940Line line,
                                       Map<String, Integer> occurrences) {
        return new BankLine(
            externalId(statement, line, occurrences),
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
     * Derived from what the transaction owns, never from where it sits.
     *
     * <p>This used to be {@code account/statementNumber/index}, which was stable only for a file
     * that never changed. <strong>Both of those components move for reasons that have nothing to do
     * with the transaction.</strong> A statement re-sent with a correction prepended shifts every
     * index below it, so each of those lines gets a fresh id and re-ingests as a duplicate payment —
     * and the statement number is minted by the sending bank, which may renumber when the set of
     * currencies on an account changes. Nothing about either says <em>which transfer this is</em>.
     *
     * <p>The bank's own reference is exactly that identity, so it is used whenever there is one.
     * The old rationale for refusing it — that some banks fill it with {@code NONREF} — was a good
     * reason not to <em>rely</em> on it and a poor reason to ignore it when present: it traded a key
     * that is right for most banks against one that is wrong for every re-sent statement.
     *
     * <p>Where the reference is absent or the useless {@code NONREF} literal, the fallback is a
     * digest of the line's own content plus how many indistinguishable lines preceded it in the same
     * statement. That is stable when unrelated lines are inserted or reordered, and it still keeps
     * two genuinely identical transfers on one day as two payments.
     *
     * <p><strong>The residual, stated rather than hidden:</strong> two identical unreferenced
     * transfers split across two statements collapse into one payment. That is the price of having
     * no identity to work with, and it is the safer side — a bank that supplies no reference for two
     * identical lines has given nobody, including a human, any way to tell them apart.
     */
    private static String externalId(Mt940Statement statement, Mt940Line line,
                                     Map<String, Integer> occurrences) {
        String reference = line.bankReference();
        if (reference != null && !reference.isBlank() && !NO_REFERENCE.equalsIgnoreCase(reference.trim())) {
            return SOURCE + "/" + statement.account() + "/" + reference.trim();
        }
        String digest = digestOf(line);
        int occurrence = occurrences.merge(digest, 1, Integer::sum) - 1;
        return SOURCE + "/" + statement.account() + "/" + digest + "/" + occurrence;
    }

    /**
     * The line's own content, hashed. Every field a bank could use to distinguish two transfers goes
     * in; the position in the file does not, which is the entire point.
     */
    private static String digestOf(Mt940Line line) {
        String content = String.join("|",
            String.valueOf(line.bookingDate()), String.valueOf(line.valueDate()),
            line.amount().stripTrailingZeros().toPlainString(), String.valueOf(line.mark()),
            String.valueOf(line.remittanceInfo()), String.valueOf(line.counterpartyName()),
            String.valueOf(line.counterpartyIban()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
