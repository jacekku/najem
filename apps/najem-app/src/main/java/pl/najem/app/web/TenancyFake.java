package pl.najem.app.web;

import pl.najem.pm.domain.LegalForm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The invented quarter of the contract screen.
 *
 * <p><b>Nothing here is read from anywhere</b>, the bargain {@link DashboardFake} and
 * {@link UnitDetailFake} already record. What is unusual about this file is how LITTLE it covers:
 * {@code TenancyScreenController} fills the term, the legal form, the components, the payment day,
 * the parties, the payment reference, the balance, the arrears colour and — the card that matters
 * most — the deposit and its shortfall, all from PM, Contacts and Accounting. Four widgets have no
 * read side and only those four are here.
 *
 * <p><b>The indexation projection is the one to be careful with.</b> It renders a złoty figure
 * beside real rent, and a manager reading a projected rent as an agreed one would send a tenant a
 * notice about money nobody has agreed. So {@link #indexation} takes the REAL rent as its base and
 * marks everything it derives from it — the template flags the card, and the note says outright
 * that neither the index nor the contractual limit is stored anywhere in this application.
 *
 * <p>Tones are this application's own vocabulary — {@code paid}, {@code warn}, {@code danger},
 * {@code neutral}, {@code green} for rail dots — never a hex, which {@code TemplateHygieneTest}
 * fails the build over.
 */
final class TenancyFake {

    private TenancyFake() {
    }

    /**
     * The index the projection applies, as a percentage.
     *
     * <p>A literal, and it stands for a figure GUS publishes monthly that this application neither
     * fetches nor stores. Kept apart from {@link AccountFake#CPI_CAP}, which is the CONTRACTUAL
     * ceiling: they are different numbers that happen to be about the same clause, and collapsing
     * them would make the projection stop showing what a cap is for.
     */
    private static final BigDecimal INDEX = new BigDecimal("4.1");

    /**
     * What the rent would become if the indexation clause were applied at the current index.
     *
     * @param monthlyTotal the real figure the tenant pays today, from PM.
     * @param rent         the real base rent when the contract declares a component split, null when
     *                     it does not. Indexation applies to the rent and not to a media advance, so
     *                     an undivided contract is projected on its total and the note says so —
     *                     projecting a split contract's whole total would overstate the rise.
     */
    static Indexation indexation(BigDecimal monthlyTotal, BigDecimal rent) {
        BigDecimal base = rent != null ? rent : monthlyTotal;
        BigDecimal next = base.multiply(BigDecimal.ONE.add(INDEX.movePointLeft(2)))
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal delta = next.subtract(base);
        return new Indexation(base, next, delta, delta.multiply(BigDecimal.valueOf(12)),
            INDEX.toPlainString().replace('.', ',') + "%", AccountFake.CPI_CAP,
            rent != null ? "Liczone od czynszu bazowego — zaliczka na media nie podlega indeksacji."
                : "Liczone od całej kwoty miesięcznej, bo ta umowa nie dzieli czynszu na składniki.",
            "Ani wskaźnik CPI, ani umowny limit indeksacji nie są w tej aplikacji przechowywane. "
                + "Projekcja pokazuje, co klauzula indeksacyjna zrobiłaby przy wskaźniku "
                + INDEX.toPlainString().replace('.', ',') + "% i limicie " + AccountFake.CPI_CAP
                + " — nie jest zawiadomieniem i nikogo nie zobowiązuje.");
    }

    /**
     * @param base    real, from PM.
     * @param next    projected. Everything derived from {@link #INDEX} is invented and the card is
     *                flagged as such.
     * @param perYear twelve times the monthly difference, which is the figure a manager actually
     *                weighs a notice against.
     */
    record Indexation(BigDecimal base, BigDecimal next, BigDecimal delta, BigDecimal perYear,
                      String index, String cap, String basis, String note) {
    }

    /**
     * The two milestones between signing and the notice window.
     *
     * <p>Handed back without dates on purpose. A handover protocol and a deposit payment do have
     * real dates in the world, and this application records neither — a date invented for them
     * would sit in a rail whose first and last entries are read from the register, which is exactly
     * the arrangement that teaches a reader to trust all six.
     */
    static List<TenancyScreenController.Milestone> milestones(boolean occasional) {
        var milestones = new ArrayList<TenancyScreenController.Milestone>();
        milestones.add(new TenancyScreenController.Milestone("Protokół zdawczo-odbiorczy",
            "Odczyty liczników i stan lokalu przy przekazaniu", null, "neutral", false));
        if (occasional) {
            milestones.add(new TenancyScreenController.Milestone("Wskazanie lokalu zastępczego",
                "Wymagane przy najmie okazjonalnym — oświadczenie właściciela lokalu zastępczego",
                null, "neutral", false));
        }
        milestones.add(new TenancyScreenController.Milestone("Zawiadomienie o indeksacji",
            "Musi dotrzeć do najemcy przed końcem roku, żeby indeksacja była skuteczna",
            null, "warn", false));
        return milestones;
    }

    /**
     * One document on the contract. {@code state} is the word; {@code tone} is the pill.
     */
    record Document(String name, String kind, String state, String tone) {
    }

    /**
     * The contract's documents, and which ones exist depends on what was signed.
     *
     * <p><b>The legal form is real, so the list's SHAPE is real even though its contents are not.</b>
     * A najem okazjonalny requires a notarial submission to enforcement and a statement naming a
     * substitute dwelling; a najem zwykły requires neither, and showing them for one would tell a
     * manager to chase paperwork the law does not ask for. That is why this takes the form rather
     * than returning one list — prototype v2 makes the same distinction and calls it out.
     */
    static List<Document> documents(LegalForm form) {
        var documents = new ArrayList<Document>();
        documents.add(new Document(form == LegalForm.OKAZJONALNY
            ? "Umowa najmu okazjonalnego" : "Umowa najmu", "Umowa", "Kompletny", "paid"));
        documents.add(new Document("Protokół zdawczo-odbiorczy", "Protokół", "Kompletny", "paid"));
        if (form == LegalForm.OKAZJONALNY) {
            documents.add(new Document("Oświadczenie o poddaniu się egzekucji", "Akt notarialny",
                "Kompletny", "paid"));
            documents.add(new Document("Wskazanie lokalu zastępczego", "Oświadczenie",
                "Kompletny", "paid"));
        }
        documents.add(new Document("Aneks indeksacyjny", "Aneks", "Projekt", "warn"));
        return List.copyOf(documents);
    }
}
