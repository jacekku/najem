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
import java.util.Objects;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Mt940ImportTest {

    private static final UUID WORKSPACE = TestWorkspace.ID;

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

    private static final String CREDIT_ID = "mt940/PL61109010140000071219812874/5/1/0";
    private static final String DEBIT_ID = "mt940/PL61109010140000071219812874/5/1/1";

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
        int imported = imports.importStatement(WORKSPACE, STATEMENT);

        assertThat(imported).isEqualTo(2);
        // Scoped to this statement's number: the container is shared across test methods, so a
        // 'mt940/%' query would also see every other method's import.
        assertThat(jdbc.queryForList(
            "select external_id from acc_payment where external_id like 'mt940/%/5/1/%' order by external_id",
            String.class))
            .containsExactly(CREDIT_ID, DEBIT_ID);
    }

    @Test
    void reUploadingTheSameStatementIngestsNothingNew() {
        String text = STATEMENT.replace(":28C:5/1", ":28C:6/1");

        imports.importStatement(WORKSPACE, text);
        imports.importStatement(WORKSPACE, text);

        assertThat(jdbc.queryForObject("""
            select count(*) from acc_payment where external_id like 'mt940/%/6/1/%'
            """, Integer.class)).isEqualTo(2);
    }

    @Test
    void carriesTheWholeLineThroughToThePayment() {
        String text = STATEMENT.replace(":28C:5/1", ":28C:7/1");

        imports.importStatement(WORKSPACE, text);

        var payment = jdbc.queryForMap("""
            select amount, title, booking_date, value_date, counterparty_name, counterparty_iban,
                   bank_reference, direction, currency
            from acc_payment where external_id = ?
            """, "mt940/PL61109010140000071219812874/7/1/0");

        assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo("2500.00");
        assertThat(payment.get("title")).isEqualTo("NAJEM/T1/2026");
        assertThat(payment.get("booking_date").toString()).isEqualTo("2026-09-10");
        assertThat(payment.get("value_date").toString()).isEqualTo("2026-09-10");
        assertThat(payment.get("counterparty_name")).isEqualTo("NAJEMCA T1");
        assertThat(payment.get("counterparty_iban")).isEqualTo("PL99000000000000000000000001");
        assertThat(payment.get("bank_reference")).isEqualTo("BNP00123456");
        assertThat(payment.get("direction")).isEqualTo("CRDT");
        assertThat(payment.get("currency")).isEqualTo("PLN");
    }

    @Test
    void anOutgoingDebitIsRecordedButNeverSuggestedHoweverWellItMatches() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WORKSPACE, tenancyId, new BigDecimal("287.43"),
            LocalDate.of(2026, 9, 11), "OPLATA ZA MEDIA");

        String text = STATEMENT.replace(":28C:5/1", ":28C:8/1");
        imports.importStatement(WORKSPACE, text);

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = ?", String.class,
            "mt940/PL61109010140000071219812874/8/1/1"))
            .isEqualTo("unmatched");
    }

    @Test
    void aCreditWhoseReferenceAndAmountMatchAnOpenChargeIsSuggested() {
        var tenancyId = UUID.randomUUID();
        ledger.postRentCharge(WORKSPACE, tenancyId, new BigDecimal("2500.00"),
            LocalDate.of(2026, 9, 10), "NAJEM/T9/2026");

        String text = STATEMENT
            .replace(":28C:5/1", ":28C:9/1")
            .replace("~20NAJEM/T1/2026", "~20NAJEM/T9/2026");
        imports.importStatement(WORKSPACE, text);

        assertThat(jdbc.queryForObject(
            "select status from acc_payment where external_id = ?", String.class,
            "mt940/PL61109010140000071219812874/9/1/0"))
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
            "from acc_payment where external_id = ?", "mt940/PL61109010140000071219812874/10/1/0");
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
        String text = STATEMENT
            .replace(":28C:5/1", ":28C:11/1")
            .replace("C2500,00NTRF", "Ctwo-thousandNTRF");

        assertThatThrownBy(() -> imports.importStatement(WORKSPACE, text))
            .isInstanceOf(Mt940FormatException.class);

        assertThat(jdbc.queryForObject("""
            select count(*) from acc_payment where external_id like 'mt940/%/11/1/%'
            """, Integer.class)).isZero();
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
        imports.importStatement(WORKSPACE, STATEMENT.replace(":28C:5/1", ":28C:12/1"));

        String payload = jdbc.queryForObject("""
            select payload::text from events
            where stream_type = 'Payment' and payload->>'externalId' = ?
            """, String.class, "mt940/PL61109010140000071219812874/12/1/0");

        // A quoted ISO string, not the [2026,9,10] array a bare JavaTimeModule mapper writes.
        // Whitespace-insensitive because jsonb::text normalises to "key": "value".
        assertThat(payload.replace(" ", "")).contains("\"bookingDate\":\"2026-09-10\"");
    }
}
