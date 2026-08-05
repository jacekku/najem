package pl.najem.acc.adapter.mt940;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.application.IngestionService;
import pl.najem.acc.application.LedgerService;
import pl.najem.acc.application.WarningService;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.mt940.Mt940FormatException;

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;


import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Mt940ImportTest {

    private static final UUID WORKSPACE = TestWorkspace.ID;
    private static final String ACCOUNT = "PL61109010140000071219812874";

    /** A credit and a debit on one statement, in the shape a Polish bank sends. */
    private static final String STATEMENT = """
        :20:NAJEM5
        :25:PL61109010140000071219812874
        :28C:5/1
        :60F:C260910PLN0,00
        :61:2609100910C2500,00NTRFNONREF//BNP00123456
        :86:~20NAJEM/T1/2026~32NAJEMCA T1~38PL99000000000000000000000001
        :61:2609110911D287,43NTRFNONREF//BNP00999999
        :86:~20OPLATA ZA MEDIA~32PGNIG OBROT~38PL99000000000000000000000002
        :62F:C260911PLN2212,57
        -
        """;

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static LedgerService ledger;
    static Mt940Import imports;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, applicationMapper(), registry);
        ledger = new LedgerService(store, jdbc, new WarningService(jdbc));
        imports = new Mt940Import(new IngestionService((since, iban) -> List.of(), store, jdbc));
    }

    /**
     * Built the way {@code apps/najem-app} builds it, not the way this module's other tests do.
     *
     * <p>Boot's auto-configured mapper disables {@code WRITE_DATES_AS_TIMESTAMPS}, so production
     * stores {@code "2026-09-10"}; a bare {@code new ObjectMapper().registerModule(JavaTimeModule)}
     * stores {@code [2026,9,10]}. Reporting reads these payloads as JSON, so a test using the other
     * encoding would be green about bytes the application never writes. See topic seq 116/119.
     */
    private static ObjectMapper applicationMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void importsEveryLineOfTheStatementUnderADeterministicId() {
        int imported = imports.importStatement(WORKSPACE, statement("A"));

        assertThat(imported).isEqualTo(2);
        assertThat(idsAmong(creditId("A"), debitId("A")))
            .containsExactly(creditId("A"), debitId("A"));
    }

    @Test
    void reUploadingTheSameStatementIngestsNothingNew() {
        String text = statement("B");

        imports.importStatement(WORKSPACE, text);
        imports.importStatement(WORKSPACE, text);

        assertThat(paymentsReferencing("BNPCB", "BNPDB")).isEqualTo(2);
    }

    /**
     * The case the positional key could not survive.
     *
     * <p>A bank re-sends a statement with a correcting entry inserted above the lines already
     * imported. Nothing about those lines changed — same money, same dates, same counterparties —
     * but every one of them moved down a row. Under {@code account/statementNumber/index} that made
     * each of them a new id and therefore a new payment, so a manager's books gained a second copy
     * of every transfer on the statement and the arrears board went green on money that arrived
     * once.
     *
     * <p>The id now comes from what the transaction owns, so only the genuinely new line is new.
     */
    @Test
    void aStatementResentWithACorrectionPrependedDoesNotDuplicateTheLinesBelowIt() {
        String original = statement("P");
        String corrected = original.replace(":61:2609100910C2500,00",
            """
            :61:2609090909C100,00NTRFNONREF//BNPCORRP
            :86:~20NAJEM/T0/2026~32NAJEMCA T0~38PL99000000000000000000000003
            :61:2609100910C2500,00""");

        imports.importStatement(WORKSPACE, original);
        imports.importStatement(WORKSPACE, corrected);

        assertThat(paymentsReferencing("BNPCP", "BNPDP"))
            .as("the two original lines must still be two payments, not four")
            .isEqualTo(2);
        assertThat(paymentsReferencing("BNPCORRP"))
            .as("the correction is genuinely new and must be ingested")
            .isEqualTo(1);
    }

    /**
     * The statement number is the sending bank's, not the transaction's.
     *
     * <p>@najem-fakebank derives it from the set of currencies on the account, so booking a transfer
     * in a currency the account had not seen renumbers the statement every other line already
     * belongs to — nothing anyone would call a bug at the time. Under a key containing that number,
     * every payment already ingested would arrive again as new.
     */
    @Test
    void aStatementRenumberedByTheBankDoesNotDuplicateWhatItAlreadySent() {
        String text = statement("N");

        imports.importStatement(WORKSPACE, text);
        imports.importStatement(WORKSPACE, text.replace(":28C:5/1", ":28C:2/1"));

        assertThat(paymentsReferencing("BNPCN", "BNPDN")).isEqualTo(2);
    }

    /**
     * NONREF is the literal a bank sends when it has no reference to give, so it identifies nothing
     * and every line carrying it would collide with every other. Those lines fall back to a digest
     * of their own content, which is still stable when an unrelated line is inserted above them.
     */
    @Test
    void aLineTheBankGaveNoReferenceForIsIdentifiedByItsOwnContent() {
        imports.importStatement(WORKSPACE, UNREFERENCED);
        imports.importStatement(WORKSPACE, UNREFERENCED.replace(":61:2609100910C1500,00",
            """
            :61:2609090909C77,00NTRFNONREF//NONREF
            :86:~20INNA WPLATA~32KTOS INNY~38PL99000000000000000000000009
            :61:2609100910C1500,00"""));

        // Counted per title rather than summed. A total of two is also what you get when the
        // original line duplicates and the genuinely new one is swallowed in its place — the two
        // failures cancel, and an assertion on the sum calls that success.
        assertThat(paymentsTitled("NAJEM/U1/2026"))
            .as("the line that was already imported must not arrive again")
            .isEqualTo(1);
        assertThat(paymentsTitled("INNA WPLATA"))
            .as("the line that is genuinely new must not be mistaken for one already seen")
            .isEqualTo(1);
    }

    /**
     * Two transfers can be identical in every field a statement carries — same day, same amount,
     * same payer, same title — and still be two payments of real money. With no reference to tell
     * them apart, the count of indistinguishable lines seen so far does it.
     */
    @Test
    void twoIdenticalUnreferencedTransfersStayTwoPayments() {
        imports.importStatement(WORKSPACE, TWINS);

        assertThat(paymentsTitled("BLIZNIACZA WPLATA")).isEqualTo(2);
    }

    @Test
    void carriesTheWholeLineThroughToThePayment() {
        imports.importStatement(WORKSPACE, statement("C"));

        var payment = jdbc.queryForMap("""
            select amount, title, booking_date, value_date, counterparty_name, counterparty_iban,
                   bank_reference, direction, currency
            from acc_payment where external_id = ?
            """, creditId("C"));

        assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo("2500.00");
        assertThat(payment.get("title")).isEqualTo("NAJEM/T1/2026");
        assertThat(payment.get("booking_date").toString()).isEqualTo("2026-09-10");
        assertThat(payment.get("value_date").toString()).isEqualTo("2026-09-10");
        assertThat(payment.get("counterparty_name")).isEqualTo("NAJEMCA T1");
        assertThat(payment.get("counterparty_iban")).isEqualTo("PL99000000000000000000000001");
        assertThat(payment.get("bank_reference")).isEqualTo("BNPCC");
        assertThat(payment.get("direction")).isEqualTo("CRDT");
        assertThat(payment.get("currency")).isEqualTo("PLN");
    }

    @Test
    void anOutgoingDebitIsRecordedButNeverSuggestedHoweverWellItMatches() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WORKSPACE, tenancyId, new BigDecimal("287.43"),
            LocalDate.of(2026, 9, 11), "OPLATA ZA MEDIA");

        imports.importStatement(WORKSPACE, statement("D"));

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = ?", String.class, debitId("D")))
            .isEqualTo("unmatched");
    }

    @Test
    void aCreditWhoseReferenceAndAmountMatchAnOpenChargeIsSuggested() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WORKSPACE, tenancyId, new BigDecimal("2500.00"),
            LocalDate.of(2026, 9, 10), "NAJEM/T9/2026");

        imports.importStatement(WORKSPACE,
            statement("E").replace("~20NAJEM/T1/2026", "~20NAJEM/T9/2026"));

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = ?", String.class, creditId("E")))
            .isEqualTo("suggested");
    }

    @Test
    void aFreeTextInformationFieldIngestsWithoutACounterparty() {
        String text = """
            :20:NAJEM10
            :25:PL61109010140000071219812874
            :28C:10/1
            :60F:C260910PLN0,00
            :61:2609100910C1000,00NTRFNONREF//BNP00111111
            :86:PRZELEW NA RACHUNEK
            :62F:C260910PLN1000,00
            -
            """;

        imports.importStatement(WORKSPACE, text);

        var payment = jdbc.queryForMap("select title, counterparty_name, counterparty_iban " +
            "from acc_payment where external_id = ?", "mt940/" + ACCOUNT + "/BNP00111111");
        assertThat(payment.get("title")).isEqualTo("PRZELEW NA RACHUNEK");
        assertThat(payment.get("counterparty_name")).isNull();
        assertThat(payment.get("counterparty_iban")).isNull();
    }

    /**
     * The rollback behaviour itself is NOT exercised here and this test does not claim it is.
     *
     * <p>This suite constructs {@link Mt940Import} directly, so no Spring proxy is involved and
     * {@code @Transactional} has no effect on anything above. What this pins is that the annotation
     * is present, so a refactor cannot drop it silently — the behaviour it buys is only observable
     * through the container, which is e2e's job. Stated rather than implied, because a test that
     * looks like it covers rollback and doesn't is worse than no test.
     */
    @Test
    void theImportIsAnnotatedSoEveryLineSharesOneTransaction() throws Exception {
        assertThat(Mt940Import.class.getMethod("importStatement", UUID.class, String.class)
            .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
            .isTrue();
    }

    @Test
    void unreadableTextIsRejectedAndIngestsNothingAtAll() {
        String text = statement("G").replace("C2500,00NTRF", "Ctwo-thousandNTRF");

        assertThatThrownBy(() -> imports.importStatement(WORKSPACE, text))
            .isInstanceOf(Mt940FormatException.class);

        assertThat(count(creditId("G"), debitId("G"))).isZero();
    }

    /**
     * EVERY mutating endpoint in this module requires a workspace, and no read is changed.
     *
     * <p>Deliberately <strong>discovered</strong> rather than enumerated. An enumeration is a
     * fix-list: it says nothing about the next endpoint someone writes, and this module has already
     * grown two write endpoints carrying the defect an hour after it was ruled on. A list would have
     * stayed green through both. This scans the adapter packages, so a new mapping fails here on the
     * day it is added rather than on the day someone re-audits.
     *
     * <p>It asserts the annotation rather than a 400, because a status code cannot distinguish
     * "required" from "defaulted to something that happens to work" — and the default is how the old
     * behaviour would return with no visible change to the method body.
     *
     * <p><strong>This test enforces the interim mechanism, and one day that will be wrong.</strong>
     * The header is a stand-in until the workspace comes from the verified token and is checked
     * against the caller's memberships. When these endpoints stop taking a header and receive a
     * resolved workspace instead, this test goes red across the module — and the cheapest way to
     * green it will be to put the headers back. <strong>Widen the assertion to accept a resolved
     * workspace parameter; do not re-add headers.</strong> The invariant is that a write cannot
     * obtain a workspace by omission, not that a write takes a header.
     */
    @Test
    void everyMutatingEndpointInThisModuleRequiresAWorkspace() throws Exception {
        var mutating = List.of(PostMapping.class, PutMapping.class,
            PatchMapping.class, DeleteMapping.class);
        var controllers = controllerClasses();

        assertThat(controllers)
            .as("the scan must actually find controllers, or this test passes vacuously")
            .hasSizeGreaterThanOrEqualTo(5);

        var offenders = new ArrayList<String>();
        int checked = 0;
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                boolean isWrite = mutating.stream().anyMatch(method::isAnnotationPresent);
                if (!isWrite) {
                    continue;
                }
                checked++;
                var header = Arrays.stream(method.getParameters())
                    .map(param -> param.getAnnotation(RequestHeader.class))
                    .filter(Objects::nonNull)
                    .filter(h -> "X-Workspace-Id".equals(h.value()) || "X-Workspace-Id".equals(h.name()))
                    .findFirst();
                if (header.isEmpty()) {
                    offenders.add(controller.getSimpleName() + "." + method.getName() + " takes no workspace header");
                } else if (!header.get().required()) {
                    offenders.add(controller.getSimpleName() + "." + method.getName() + " defaults its workspace");
                }
            }
        }

        assertThat(checked).as("no mutating endpoints were examined").isPositive();
        assertThat(offenders).as("a write must name the workspace it modifies").isEmpty();
    }

    /** Every {@code @RestController} under this module's adapter packages, found on the classpath. */
    private static List<Class<?>> controllerClasses() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var found = new ArrayList<Class<?>>();
        for (var candidate : scanner.findCandidateComponents("pl.najem.acc.adapter")) {
            found.add(Class.forName(candidate.getBeanClassName()));
        }
        return found;
    }

    /**
     * The payload Reporting reads. Pinned here because this module's other tests serialize with a
     * mapper the application never uses, so they cannot notice the encoding changing.
     */
    @Test
    void storesDatesInTheEncodingTheApplicationWrites() {
        imports.importStatement(WORKSPACE, statement("H"));

        String payload = jdbc.queryForObject("""
            select payload::text from events
            where stream_type = 'Payment' and payload->>'externalId' = ?
            """, String.class, creditId("H"));

        // A quoted ISO string, not the [2026,9,10] array a bare JavaTimeModule mapper writes.
        // Whitespace-insensitive because jsonb::text normalises to "key": "value".
        assertThat(payload.replace(" ", "")).contains("\"bookingDate\":\"2026-09-10\"");
    }

    /** Every line carries NONREF, so nothing here can be identified by a bank reference. */
    private static final String UNREFERENCED = """
        :20:NAJEMU
        :25:PL61109010140000071219812874
        :28C:20/1
        :60F:C260910PLN0,00
        :61:2609100910C1500,00NTRFNONREF//NONREF
        :86:~20NAJEM/U1/2026~32NAJEMCA U1~38PL99000000000000000000000004
        :62F:C260910PLN1500,00
        -
        """;

    /** Two lines a statement cannot tell apart, and neither can anyone reading it. */
    private static final String TWINS = """
        :20:NAJEMW
        :25:PL61109010140000071219812874
        :28C:21/1
        :60F:C260910PLN0,00
        :61:2609100910C640,00NTRFNONREF//NONREF
        :86:~20BLIZNIACZA WPLATA~32BLIZNIAK~38PL99000000000000000000000005
        :61:2609100910C640,00NTRFNONREF//NONREF
        :86:~20BLIZNIACZA WPLATA~32BLIZNIAK~38PL99000000000000000000000005
        :62F:C260910PLN1280,00
        -
        """;

    /**
     * The shared statement with per-test bank references.
     *
     * <p>These used to be isolated by giving each method its own {@code :28C:} statement number,
     * which worked only because the number was part of the id. It is not any more — and that is the
     * point of the change — so the reference is what varies. The container is shared across methods,
     * and two methods importing the same reference would silently deduplicate against each other.
     */
    private static String statement(String tag) {
        return STATEMENT.replace("BNP00123456", "BNPC" + tag).replace("BNP00999999", "BNPD" + tag);
    }

    private static String creditId(String tag) {
        return "mt940/" + ACCOUNT + "/BNPC" + tag;
    }

    private static String debitId(String tag) {
        return "mt940/" + ACCOUNT + "/BNPD" + tag;
    }

    private static List<String> idsAmong(String... externalIds) {
        return jdbc.queryForList("select external_id from acc_payment where external_id in ("
            + placeholders(externalIds) + ") order by external_id", String.class,
            (Object[]) externalIds);
    }

    /**
     * How many payments the module is holding for these bank references.
     *
     * <p>Counted by what the bank said, not by the id this module derives, on purpose. The
     * duplication tests are about whether one transfer became two payments — an invariant that has
     * to survive any future change to how the key is spelled. Asserting the id here instead would
     * make every one of them fail on a rename and pass on a genuine duplication, which is backwards.
     * The id format is pinned once, deliberately, in
     * {@link #importsEveryLineOfTheStatementUnderADeterministicId()}.
     */
    private static Integer paymentsReferencing(String... bankReferences) {
        return jdbc.queryForObject("select count(*) from acc_payment where bank_reference in ("
            + placeholders(bankReferences) + ")", Integer.class, (Object[]) bankReferences);
    }

    private static Integer paymentsTitled(String title) {
        return jdbc.queryForObject(
            "select count(*) from acc_payment where title = ?", Integer.class, title);
    }

    private static Integer count(String... externalIds) {
        return jdbc.queryForObject("select count(*) from acc_payment where external_id in ("
            + placeholders(externalIds) + ")", Integer.class, (Object[]) externalIds);
    }

    private static String placeholders(String[] values) {
        return String.join(",", Collections.nCopies(values.length, "?"));
    }
}
