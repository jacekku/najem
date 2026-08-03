# FakeBank Phase 1 Implementation Plan — Deterministic Scenario Seeding

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn FakeBank into a deterministic test-fixture engine that seeds thirteen named bank-statement scenarios on demand, so accounting's matching ladder (tiers 1–4) and the e2e suite have realistic, reproducible bank data.

**Architecture:** Three collaborators inside `apps/fakebank`, each with one job. `ScenarioCatalog` is a pure, Spring-free function from `(scenario name, parameters)` to a list of transactions — all domain knowledge about "what a late payment looks like" lives here. `TransactionStore` is the in-memory map extracted out of `AccountsController` so two controllers can share it. `ScenarioController` exposes `POST /api/scenarios`, which validates the name, calls the catalog, and stores the result. The existing `AccountsController` endpoints keep their exact behaviour and wire shape.

**Tech Stack:** Java 21, Spring Boot 3.3.5 (web only), JUnit 5, AssertJ, Spring MockMvc (`@WebMvcTest`). No database, no persistence, no new dependencies.

## Global Constraints

- Build env exports required before any Gradle command (coordinator convention 1, gate message seq 15):
  `export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"`,
  `export DOCKER_HOST="unix:///Users/jaca/.colima/default/docker.sock"`,
  `export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"`.
- Work happens in the worktree `/Users/jaca/Repos/najem-wt/najem-fakebank` on branch `najem-fakebank/scenario-seeding`. The main checkout `/Users/jaca/Repos/NAJEM` is coordinator-only (seq 30).
- NEVER `git add -A` or `git add .` — stage explicit paths only (seq 23).
- Do NOT touch: `contracts/`, `platform/`, `settings.gradle.kts`, root `build.gradle.kts`, `PlatformConfig`, `apps/najem-app/**`, other modules, or e2e test logic (seq 30 pt 2d).
- Money = `BigDecimal`, dates = `LocalDate`. No floats. All computed amounts use `setScale(2, RoundingMode.HALF_UP)`.
- Determinism over realism wherever the two conflict (human ruling, seq 29). No randomness, no clock reads, no persistence — the same request always yields byte-identical output.
- `creditDebitIndicator` uses the ISO 20022 literals `CRDT` / `DBIT`. Amounts are ALWAYS positive; direction is carried solely by the indicator, never duplicated as a sign (coordinator-endorsed, seq 37).
- The existing `GET`/`POST /api/accounts/{iban}/transactions` endpoints stay backward-compatible: `WalkingSkeletonTest` and accounting's `FakeBankAdapter` must keep working untouched.
- MT940 export is explicitly OUT OF SCOPE — deferred to Phase 2 to land with its parser (human ruling, seq 29).
- Flyway: FakeBank owns no schema and claims no migration version.
- Commits: imperative mood, sign as the repo user, never mention any AI/LLM tool.

## File Structure

| File | Responsibility |
|---|---|
| `apps/fakebank/src/main/java/pl/najem/fakebank/BankTransactionDto.java` | *(modify)* Wire shape of one bank line. Gains six nullable fields. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/TransactionStore.java` | *(create)* In-memory per-IBAN transaction storage. Extracted from `AccountsController`. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/AccountsController.java` | *(modify)* Same two endpoints, now delegating storage to `TransactionStore`. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioRequest.java` | *(create)* Request body of `POST /api/scenarios`. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioResult.java` | *(create)* Response body of `POST /api/scenarios`. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioCatalog.java` | *(create)* Pure generator: all thirteen scenarios and the determinism helpers. The only file holding fixture domain knowledge. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/UnknownScenarioException.java` | *(create)* Signals an unknown scenario name; mapped to HTTP 400. |
| `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioController.java` | *(create)* HTTP surface for seeding a scenario. |
| `apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioCatalogTest.java` | *(create)* The substantive assertions — exact lines per scenario. Pure, no Spring. |
| `apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioControllerTest.java` | *(create)* HTTP edges only: 400 on unknown name, seeded lines visible through the existing GET. |
| `apps/fakebank/src/test/java/pl/najem/fakebank/AccountsControllerTest.java` | *(modify)* Gains a case proving the widened fields round-trip and that omitting them still works. |
| `apps/fakebank/README.md` | *(create)* Scenario catalogue reference for accounting. |

`ScenarioCatalog` is the one file that will grow; at thirteen small scenarios it stays readable. If a future scenario needs real logic rather than a few lines of list-building, that scenario earns its own class — not before.

---

### Task 1: Widen `BankTransactionDto` with six nullable fields

The field list accounting requested (seq 33), agreed at seq 34. This lands first and merges on its own so accounting can start against the real shape while the generators are still being written.

**Files:**
- Modify: `apps/fakebank/src/main/java/pl/najem/fakebank/BankTransactionDto.java`
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/AccountsControllerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BankTransactionDto(String id, BigDecimal amount, String title, LocalDate bookingDate, String counterpartyName, String counterpartyIban, String bankReference, LocalDate valueDate, String creditDebitIndicator, String currency)` — the canonical constructor, used by Jackson. Plus `static BankTransactionDto plain(String id, BigDecimal amount, String title, LocalDate bookingDate)` returning the same record with all six new fields null. Every later task builds lines through the canonical constructor.

A static factory is used rather than a compact secondary constructor: two constructors on a record make Jackson's canonical-constructor selection ambiguous, and this code is deserialized on every seed call.

- [ ] **Step 1: Write the failing test**

Add to `AccountsControllerTest`:

```java
    @Test
    void roundTripsTheWidenedFieldsAndToleratesTheirAbsence() throws Exception {
        mvc.perform(post("/api/accounts/PL62/transactions").contentType(APPLICATION_JSON)
                .content("""
                    {"id":"tx-wide","amount":2500,"title":"NAJEM/M1/2026","bookingDate":"2026-09-03",
                     "counterpartyName":"JAN KOWALSKI","counterpartyIban":"PL27114020040000300201355387",
                     "bankReference":"BNP00012345","valueDate":"2026-09-04",
                     "creditDebitIndicator":"CRDT","currency":"PLN"}
                    """))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL62/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].counterpartyName").value("JAN KOWALSKI"))
            .andExpect(jsonPath("$[0].counterpartyIban").value("PL27114020040000300201355387"))
            .andExpect(jsonPath("$[0].bankReference").value("BNP00012345"))
            .andExpect(jsonPath("$[0].valueDate").value("2026-09-04"))
            .andExpect(jsonPath("$[0].creditDebitIndicator").value("CRDT"))
            .andExpect(jsonPath("$[0].currency").value("PLN"));

        // The Phase 0 payload shape must still be accepted, with the new fields absent.
        mvc.perform(post("/api/accounts/PL63/transactions").contentType(APPLICATION_JSON)
                .content("""
                    {"id":"tx-narrow","amount":2500,"title":"NAJEM/M1/2026","bookingDate":"2026-09-03"}
                    """))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL63/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("tx-narrow"))
            .andExpect(jsonPath("$[0].counterpartyIban").doesNotExist());
    }
```

The second half is the important half: it is the regression guard for accounting's existing `FakeBankAdapter`.

Note `jsonPath("$[0].counterpartyIban").doesNotExist()` requires null fields to be omitted from the response — Step 3 adds the annotation that does this.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:fakebank:test --tests '*AccountsControllerTest'`
Expected: FAIL — compilation error, or the widened fields are absent from the response.

- [ ] **Step 3: Write minimal implementation**

`BankTransactionDto.java`:

```java
package pl.najem.fakebank;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One bank statement line as FakeBank serves it.
 *
 * <p>The first four fields are always present — they are the Phase 0 shape that
 * accounting's FakeBankAdapter already consumes. The remaining six are nullable
 * additions requested by najem-accounting for matching ladder tiers 2-4; they are
 * omitted from the JSON when null so the Phase 0 payload is reproduced exactly.
 *
 * <p>Amounts are ALWAYS positive. Direction lives solely in {@code creditDebitIndicator}
 * ({@code CRDT} / {@code DBIT}, ISO 20022) — never duplicated as a sign.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BankTransactionDto(String id,
                                 BigDecimal amount,
                                 String title,
                                 LocalDate bookingDate,
                                 String counterpartyName,
                                 String counterpartyIban,
                                 String bankReference,
                                 LocalDate valueDate,
                                 String creditDebitIndicator,
                                 String currency) {

    /** A line carrying only the Phase 0 fields. */
    public static BankTransactionDto plain(String id, BigDecimal amount, String title, LocalDate bookingDate) {
        return new BankTransactionDto(id, amount, title, bookingDate, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :apps:fakebank:test --tests '*AccountsControllerTest'`
Expected: PASS, both test methods.

- [ ] **Step 5: Prove nothing downstream broke**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, including `WalkingSkeletonTest` — that test exercises accounting's `FakeBankAdapter` against this DTO and is the real proof of backward compatibility.

- [ ] **Step 6: Commit**

```bash
git add apps/fakebank/src/main/java/pl/najem/fakebank/BankTransactionDto.java \
        apps/fakebank/src/test/java/pl/najem/fakebank/AccountsControllerTest.java
git commit -m "Widen bank transaction with counterparty, reference and currency fields"
```

- [ ] **Step 7: Merge and announce**

Rebase on latest main, run `./gradlew build` again, then merge per the self-merge mechanic in force (see the "Merge mechanics" note at the end of this plan — the seq 30 `git push . HEAD:main` recipe is currently blocked and awaiting a coordinator ruling; if it is still blocked, post READY-FOR-REVIEW instead).
Post `STATUS MERGED` to `najem-build` with the commit hash and a one-line summary, tagging `@najem-accounting` — this is the change they are waiting on.

---

### Task 2: Extract `TransactionStore`

`AccountsController` currently owns the map. `ScenarioController` needs to write to the same storage, so it moves to a shared bean. Pure refactor — no behaviour change.

**Files:**
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/TransactionStore.java`
- Modify: `apps/fakebank/src/main/java/pl/najem/fakebank/AccountsController.java`
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/AccountsControllerTest.java` (existing tests are the regression net)

**Interfaces:**
- Consumes: `BankTransactionDto` (Task 1).
- Produces:
  ```java
  @Component
  public class TransactionStore {
      public void add(String iban, BankTransactionDto transaction);
      public void addAll(String iban, List<BankTransactionDto> transactions);
      public List<BankTransactionDto> find(String iban, LocalDate since);  // since == null -> all
  }
  ```

- [ ] **Step 1: Write the failing test**

`AccountsControllerTest` is a `@WebMvcTest(AccountsController.class)`, which does not create arbitrary `@Component` beans. Add the import and the annotation that supplies the new bean, so the existing tests become the regression net for the refactor:

```java
import org.springframework.context.annotation.Import;

@WebMvcTest(AccountsController.class)
@Import(TransactionStore.class)
class AccountsControllerTest {
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:fakebank:test --tests '*AccountsControllerTest'`
Expected: FAIL — compilation error, `TransactionStore` does not exist.

- [ ] **Step 3: Write minimal implementation**

`TransactionStore.java`:

```java
package pl.najem.fakebank;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory statement storage, one list per IBAN. Nothing is persisted; restart clears it. */
@Component
public class TransactionStore {

    private final Map<String, List<BankTransactionDto>> accounts = new ConcurrentHashMap<>();

    public void add(String iban, BankTransactionDto transaction) {
        accounts.computeIfAbsent(iban, key -> new CopyOnWriteArrayList<>()).add(transaction);
    }

    public void addAll(String iban, List<BankTransactionDto> transactions) {
        accounts.computeIfAbsent(iban, key -> new CopyOnWriteArrayList<>()).addAll(transactions);
    }

    /** @param since inclusive lower bound on booking date; null means no bound. */
    public List<BankTransactionDto> find(String iban, LocalDate since) {
        return accounts.getOrDefault(iban, List.of()).stream()
            .filter(transaction -> since == null || !transaction.bookingDate().isBefore(since))
            .toList();
    }
}
```

`AccountsController.java` — replace the map field and both method bodies, leaving the mappings untouched:

```java
package pl.najem.fakebank;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/accounts/{iban}/transactions")
public class AccountsController {

    private final TransactionStore store;

    public AccountsController(TransactionStore store) {
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public void seed(@PathVariable String iban, @RequestBody BankTransactionDto transaction) {
        store.add(iban, transaction);
    }

    @GetMapping
    public List<BankTransactionDto> list(@PathVariable String iban,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return store.find(iban, since);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :apps:fakebank:test`
Expected: PASS — both existing test methods, unchanged in substance.

- [ ] **Step 5: Commit**

```bash
git add apps/fakebank/src/main/java/pl/najem/fakebank/TransactionStore.java \
        apps/fakebank/src/main/java/pl/najem/fakebank/AccountsController.java \
        apps/fakebank/src/test/java/pl/najem/fakebank/AccountsControllerTest.java
git commit -m "Extract transaction store from accounts controller"
```

---

### Task 3: `ScenarioCatalog` — determinism helpers and the five payment-timing scenarios

**Files:**
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioRequest.java`
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/UnknownScenarioException.java`
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioCatalog.java`
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioCatalogTest.java`

**Interfaces:**
- Consumes: `BankTransactionDto` (Task 1).
- Produces:
  ```java
  public record ScenarioRequest(String name, String iban, String reference,
                                BigDecimal amount, LocalDate anchorDate) {}

  public class UnknownScenarioException extends RuntimeException {
      public UnknownScenarioException(String name);
      public String name();
  }

  @Component
  public class ScenarioCatalog {
      public Set<String> names();                                  // all known scenario names
      public List<BankTransactionDto> generate(ScenarioRequest request);  // throws UnknownScenarioException
  }
  ```

**Design rules every scenario obeys** (asserted by the tests):

- `externalId` is `"<scenario-name>/<sanitised reference>/<index>"`, where sanitising replaces every character outside `[A-Za-z0-9-]` with `-`. Re-seeding the same request produces the same ids, which is what makes accounting's dedupe path testable.
- `bankReference` is `"BNP" + an 8-digit zero-padded value derived from the externalId's `String.hashCode()`. `String.hashCode()` is specified by the JLS, so this is stable across JVMs and machines. It is deliberately unrelated to the tenant's `title` reference — telling those two apart is the whole point of the field.
- `counterpartyIban` is `"PL" + 26 digits` derived the same way from a seed string, so the tenant's account is stable across months while differing between references.
- `currency` defaults to `"PLN"`, `creditDebitIndicator` to `"CRDT"`, `valueDate` to `bookingDate` — unless a scenario deliberately departs, which is then the point of that scenario.

- [ ] **Step 1: Write the failing test**

`ScenarioCatalogTest.java`:

```java
package pl.najem.fakebank;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCatalogTest {

    private static final String IBAN = "PL61109010140000071219812874";
    private static final String REFERENCE = "NAJEM/M1/2026";
    private static final BigDecimal AMOUNT = new BigDecimal("2500.00");
    private static final LocalDate DUE = LocalDate.of(2026, 9, 1);

    private final ScenarioCatalog catalog = new ScenarioCatalog();

    private List<BankTransactionDto> generate(String name) {
        return catalog.generate(new ScenarioRequest(name, IBAN, REFERENCE, AMOUNT, DUE));
    }

    @Test
    void onTimePaysExactlyOnTheDueDate() {
        List<BankTransactionDto> lines = generate("on-time");

        assertThat(lines).hasSize(1);
        BankTransactionDto line = lines.getFirst();
        assertThat(line.amount()).isEqualByComparingTo("2500.00");
        assertThat(line.title()).isEqualTo(REFERENCE);
        assertThat(line.bookingDate()).isEqualTo(DUE);
        assertThat(line.valueDate()).isEqualTo(DUE);
        assertThat(line.creditDebitIndicator()).isEqualTo("CRDT");
        assertThat(line.currency()).isEqualTo("PLN");
        assertThat(line.counterpartyIban()).startsWith("PL").hasSize(28);
        assertThat(line.bankReference()).startsWith("BNP").isNotEqualTo(REFERENCE);
    }

    @Test
    void lateBooksAfterTheDueDateAndSplitsValueDate() {
        List<BankTransactionDto> lines = generate("late");

        assertThat(lines).hasSize(1);
        BankTransactionDto line = lines.getFirst();
        assertThat(line.bookingDate()).isEqualTo(DUE.plusDays(6));
        assertThat(line.valueDate()).isEqualTo(DUE.plusDays(8));
        assertThat(line.amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void partialPaysSixtyPercent() {
        List<BankTransactionDto> lines = generate("partial");

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().amount()).isEqualByComparingTo("1500.00");
    }

    @Test
    void partialThenTopupSettlesInTwoTransfersSummingToTheCharge() {
        List<BankTransactionDto> lines = generate("partial-then-topup");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount()).isEqualByComparingTo("1500.00");
        assertThat(lines.get(0).bookingDate()).isEqualTo(DUE);
        assertThat(lines.get(1).amount()).isEqualByComparingTo("1000.00");
        assertThat(lines.get(1).bookingDate()).isEqualTo(DUE.plusDays(4));
        assertThat(lines.get(0).amount().add(lines.get(1).amount())).isEqualByComparingTo(AMOUNT);
    }

    @Test
    void overpayPaysTwentyPercentTooMuch() {
        assertThat(generate("overpay").getFirst().amount()).isEqualByComparingTo("3000.00");
    }

    @Test
    void externalIdsAreDeterministicAndUniqueWithinAScenario() {
        List<BankTransactionDto> first = generate("partial-then-topup");
        List<BankTransactionDto> second = generate("partial-then-topup");

        assertThat(first.stream().map(BankTransactionDto::id))
            .containsExactly("partial-then-topup/NAJEM-M1-2026/0", "partial-then-topup/NAJEM-M1-2026/1");
        assertThat(second.stream().map(BankTransactionDto::id))
            .containsExactlyElementsOf(first.stream().map(BankTransactionDto::id).toList());
    }

    @Test
    void theSameTenantKeepsTheSameAccountAcrossScenarios() {
        assertThat(generate("on-time").getFirst().counterpartyIban())
            .isEqualTo(generate("late").getFirst().counterpartyIban());
    }

    @Test
    void unknownScenarioIsRejected() {
        assertThatThrownBy(() -> generate("no-such-scenario"))
            .isInstanceOf(UnknownScenarioException.class)
            .hasMessageContaining("no-such-scenario");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:fakebank:test --tests '*ScenarioCatalogTest'`
Expected: FAIL — compilation error, none of the three production types exist.

- [ ] **Step 3: Write minimal implementation**

`ScenarioRequest.java`:

```java
package pl.najem.fakebank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Parameters for seeding one scenario.
 *
 * @param name       scenario name from {@link ScenarioCatalog#names()}
 * @param iban       account to seed into
 * @param reference  the tenancy's payment reference, as it would appear in a transfer title
 * @param amount     the charge being paid; scenarios express their amounts relative to it
 * @param anchorDate the charge's due date; scenarios express their dates relative to it
 */
public record ScenarioRequest(String name, String iban, String reference,
                              BigDecimal amount, LocalDate anchorDate) {
}
```

`UnknownScenarioException.java`:

```java
package pl.najem.fakebank;

public class UnknownScenarioException extends RuntimeException {

    private final String name;

    public UnknownScenarioException(String name) {
        super("Unknown scenario: " + name);
        this.name = name;
    }

    public String name() {
        return name;
    }
}
```

`ScenarioCatalog.java` — the five scenarios of this task plus the shared helpers. Task 4 adds the remaining eight to the same `switch`:

```java
package pl.najem.fakebank;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Generates the named bank-statement scenarios that accounting's matching ladder is tested against.
 *
 * <p>Pure and deterministic by contract: no clock, no randomness, no state. The same request always
 * produces byte-identical lines, so re-seeding is idempotent from a consumer's point of view.
 */
@Component
public class ScenarioCatalog {

    private static final String CREDIT = "CRDT";
    private static final String DEBIT = "DBIT";
    private static final String PLN = "PLN";

    private static final Set<String> NAMES = Set.of(
        "on-time", "late", "partial", "partial-then-topup", "overpay");

    public Set<String> names() {
        return NAMES;
    }

    public List<BankTransactionDto> generate(ScenarioRequest request) {
        return switch (request.name()) {
            case "on-time" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 0, 0));
            case "late" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 6, 8));
            case "partial" -> List.of(
                credit(request, 0, percentOf(request.amount(), 60), request.reference(), 0, 0));
            case "partial-then-topup" -> {
                BigDecimal first = percentOf(request.amount(), 60);
                yield List.of(
                    credit(request, 0, first, request.reference(), 0, 0),
                    credit(request, 1, request.amount().subtract(first), request.reference(), 4, 4));
            }
            case "overpay" -> List.of(
                credit(request, 0, percentOf(request.amount(), 120), request.reference(), 0, 0));
            default -> throw new UnknownScenarioException(request.name());
        };
    }

    /**
     * One credit line.
     *
     * @param index        position within the scenario; drives the deterministic external id
     * @param bookingOffset days after the anchor date the line is booked
     * @param valueOffset   days after the anchor date the line is valued
     */
    private BankTransactionDto credit(ScenarioRequest request, int index, BigDecimal amount,
                                      String title, int bookingOffset, int valueOffset) {
        return line(request, index, amount, title, bookingOffset, valueOffset, CREDIT, PLN,
            tenantName(request.reference()), syntheticIban(request.reference()));
    }

    private BankTransactionDto line(ScenarioRequest request, int index, BigDecimal amount, String title,
                                    int bookingOffset, int valueOffset, String indicator, String currency,
                                    String counterpartyName, String counterpartyIban) {
        String externalId = externalId(request.name(), request.reference(), index);
        return new BankTransactionDto(
            externalId,
            amount.setScale(2, RoundingMode.HALF_UP),
            title,
            request.anchorDate().plusDays(bookingOffset),
            counterpartyName,
            counterpartyIban,
            bankReference(externalId),
            request.anchorDate().plusDays(valueOffset),
            indicator,
            currency);
    }

    private static BigDecimal percentOf(BigDecimal amount, int percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static String externalId(String scenario, String reference, int index) {
        return scenario + "/" + sanitise(reference) + "/" + index;
    }

    private static String sanitise(String value) {
        return value.replaceAll("[^A-Za-z0-9-]", "-");
    }

    /** Bank-side reference, deliberately unrelated to the tenant's title reference. */
    private static String bankReference(String seed) {
        return "BNP" + String.format("%08d", digitsOf(seed, 100_000_000L));
    }

    /** A stable synthetic account for a tenant: same reference always yields the same IBAN. */
    private static String syntheticIban(String reference) {
        return "PL" + String.format("%026d", digitsOf("iban:" + reference, 1_000_000_000_000L));
    }

    private static String tenantName(String reference) {
        return "NAJEMCA " + sanitise(reference);
    }

    /**
     * A non-negative value derived from the seed. {@link String#hashCode()} is specified by the
     * language, so this is reproducible across JVMs and machines — which the determinism contract needs.
     */
    private static long digitsOf(String seed, long bound) {
        return Math.floorMod((long) seed.hashCode(), bound);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :apps:fakebank:test --tests '*ScenarioCatalogTest'`
Expected: PASS, all eight test methods.

- [ ] **Step 5: Commit**

```bash
git add apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioRequest.java \
        apps/fakebank/src/main/java/pl/najem/fakebank/UnknownScenarioException.java \
        apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioCatalog.java \
        apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioCatalogTest.java
git commit -m "Generate payment timing scenarios deterministically"
```

---

### Task 4: The remaining eight scenarios

Reference-quality cases (tiers 2–4), the duplicate/reversal pair, and the three lines accounting asked for at seq 33 that the Phase 0 shape could not express.

**Files:**
- Modify: `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioCatalog.java`
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioCatalogTest.java`

**Interfaces:**
- Consumes: everything from Task 3.
- Produces: `names()` grows to all thirteen: `on-time`, `late`, `partial`, `partial-then-topup`, `overpay`, `wrong-reference`, `no-reference`, `duplicate`, `reversal`, `lump-sum`, `third-party-payer`, `outgoing-debit`, `foreign-currency`.

- [ ] **Step 1: Write the failing test**

Append to `ScenarioCatalogTest`:

```java
    @Test
    void wrongReferenceKeepsTheAmountButManglesTheTitle() {
        BankTransactionDto line = generate("wrong-reference").getFirst();

        assertThat(line.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(line.title()).isEqualTo("najem m1 2026").isNotEqualTo(REFERENCE);
    }

    @Test
    void noReferenceLeavesTheTitleEmpty() {
        BankTransactionDto line = generate("no-reference").getFirst();

        assertThat(line.title()).isEmpty();
        assertThat(line.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(line.counterpartyIban()).isNotBlank();
    }

    @Test
    void duplicateSeedsTheSamePaymentTwiceUnderDistinctBankIdentifiers() {
        List<BankTransactionDto> lines = generate("duplicate");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).amount()).isEqualByComparingTo(lines.get(1).amount());
        assertThat(lines.get(0).title()).isEqualTo(lines.get(1).title());
        assertThat(lines.get(0).bookingDate()).isEqualTo(lines.get(1).bookingDate());
        assertThat(lines.get(0).id()).isNotEqualTo(lines.get(1).id());
        assertThat(lines.get(0).bankReference()).isNotEqualTo(lines.get(1).bankReference());
    }

    @Test
    void reversalCreditsThenTakesTheMoneyBack() {
        List<BankTransactionDto> lines = generate("reversal");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).creditDebitIndicator()).isEqualTo("CRDT");
        assertThat(lines.get(1).creditDebitIndicator()).isEqualTo("DBIT");
        assertThat(lines.get(1).amount())
            .as("the debit is positive; direction lives in the indicator alone")
            .isEqualByComparingTo(lines.get(0).amount());
        assertThat(lines.get(1).bookingDate()).isEqualTo(DUE.plusDays(3));
        assertThat(lines.get(1).title()).isEqualTo("ZWROT " + REFERENCE);
    }

    @Test
    void lumpSumCoversTwoReferencesInOneTransfer() {
        BankTransactionDto line = generate("lump-sum").getFirst();

        assertThat(line.amount()).isEqualByComparingTo("5000.00");
        assertThat(line.title()).isEqualTo(REFERENCE + " " + REFERENCE + "/2");
    }

    @Test
    void thirdPartyPayerUsesADifferentAccountAndNames_a_differentPerson() {
        BankTransactionDto line = generate("third-party-payer").getFirst();

        assertThat(line.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(line.title()).isEmpty();
        assertThat(line.counterpartyName()).isEqualTo("ANNA KOWALSKA");
        assertThat(line.counterpartyIban())
            .as("the payer is not the tenant, so the account must differ")
            .isNotEqualTo(generate("on-time").getFirst().counterpartyIban());
    }

    @Test
    void thirdPartyPayerKeepsTheSameAccountAcrossMonths() {
        LocalDate nextMonth = DUE.plusMonths(1);

        String september = generate("third-party-payer").getFirst().counterpartyIban();
        String october = catalog.generate(
            new ScenarioRequest("third-party-payer", IBAN, REFERENCE, AMOUNT, nextMonth))
            .getFirst().counterpartyIban();

        assertThat(october).isEqualTo(september);
    }

    @Test
    void outgoingDebitIsAUtilityPaymentThatIsNotRent() {
        BankTransactionDto line = generate("outgoing-debit").getFirst();

        assertThat(line.creditDebitIndicator()).isEqualTo("DBIT");
        assertThat(line.amount()).isEqualByComparingTo("287.43");
        assertThat(line.title()).isEqualTo("OPLATA ZA MEDIA");
        assertThat(line.counterpartyName()).isEqualTo("PGNIG OBROT DETALICZNY");
        assertThat(line.bookingDate()).isEqualTo(DUE.plusDays(1));
    }

    @Test
    void foreignCurrencyCreditsInEuro() {
        BankTransactionDto line = generate("foreign-currency").getFirst();

        assertThat(line.currency()).isEqualTo("EUR");
        assertThat(line.creditDebitIndicator()).isEqualTo("CRDT");
        assertThat(line.title()).isEqualTo(REFERENCE);
    }

    @Test
    void everyAdvertisedScenarioGenerates() {
        assertThat(catalog.names()).hasSize(13);
        assertThat(catalog.names()).allSatisfy(name -> assertThat(generate(name)).isNotEmpty());
    }

    @Test
    void everyLineCarriesAPositiveAmount() {
        assertThat(catalog.names()).allSatisfy(name ->
            assertThat(generate(name)).allSatisfy(line ->
                assertThat(line.amount()).isPositive()));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :apps:fakebank:test --tests '*ScenarioCatalogTest'`
Expected: FAIL — `UnknownScenarioException` for each new name, and `everyAdvertisedScenarioGenerates` fails on the size assertion.

- [ ] **Step 3: Write minimal implementation**

In `ScenarioCatalog`, replace `NAMES` and add the new `case` arms before `default`:

```java
    private static final Set<String> NAMES = Set.of(
        "on-time", "late", "partial", "partial-then-topup", "overpay",
        "wrong-reference", "no-reference", "duplicate", "reversal", "lump-sum",
        "third-party-payer", "outgoing-debit", "foreign-currency");
```

```java
            case "wrong-reference" -> List.of(
                credit(request, 0, request.amount(), mangle(request.reference()), 0, 0));
            case "no-reference" -> List.of(
                credit(request, 0, request.amount(), "", 0, 0));
            case "duplicate" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 0, 0),
                credit(request, 1, request.amount(), request.reference(), 0, 0));
            case "reversal" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 0, 0),
                line(request, 1, request.amount(), "ZWROT " + request.reference(), 3, 3,
                    DEBIT, PLN, tenantName(request.reference()), syntheticIban(request.reference())));
            case "lump-sum" -> List.of(
                credit(request, 0, request.amount().multiply(BigDecimal.TWO),
                    request.reference() + " " + request.reference() + "/2", 0, 0));
            case "third-party-payer" -> List.of(
                line(request, 0, request.amount(), "", 0, 0, CREDIT, PLN,
                    "ANNA KOWALSKA", syntheticIban("payer:" + request.reference())));
            case "outgoing-debit" -> List.of(
                line(request, 0, new BigDecimal("287.43"), "OPLATA ZA MEDIA", 1, 1,
                    DEBIT, PLN, "PGNIG OBROT DETALICZNY", syntheticIban("utility")));
            case "foreign-currency" -> List.of(
                line(request, 0, request.amount(), request.reference(), 0, 0, CREDIT, "EUR",
                    tenantName(request.reference()), syntheticIban(request.reference())));
```

And the mangling helper, alongside `sanitise`:

```java
    /** A reference as a careless payer would type it: separators lost, case lost. */
    private static String mangle(String reference) {
        return reference.replace("/", " ").toLowerCase();
    }
```

Note `duplicate` produces two lines with different `index` values, so their external ids and bank references differ while amount, title and dates match — a genuine bank-level duplicate rather than a trivially-deduplicated id collision.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :apps:fakebank:test --tests '*ScenarioCatalogTest'`
Expected: PASS, all nineteen test methods.

- [ ] **Step 5: Commit**

```bash
git add apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioCatalog.java \
        apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioCatalogTest.java
git commit -m "Generate reference quality, duplicate, reversal and non-rent scenarios"
```

---

### Task 5: `POST /api/scenarios`

**Files:**
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioResult.java`
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioController.java`
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioControllerTest.java`

**Interfaces:**
- Consumes: `ScenarioCatalog`, `ScenarioRequest`, `UnknownScenarioException` (Tasks 3–4); `TransactionStore` (Task 2).
- Produces:
  ```java
  public record ScenarioResult(int seeded, List<String> externalIds) {}
  // POST /api/scenarios  body: ScenarioRequest  -> 201 ScenarioResult
  //                                             -> 400 on an unknown scenario name
  ```

This test covers only the HTTP edges. The substantive per-scenario assertions live in `ScenarioCatalogTest`, where they run without Spring.

- [ ] **Step 1: Write the failing test**

`ScenarioControllerTest.java`:

```java
package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ScenarioController.class, AccountsController.class})
@Import({TransactionStore.class, ScenarioCatalog.class})
class ScenarioControllerTest {

    private static final String SEED_BODY = """
        {"name":"partial-then-topup","iban":"PL61","reference":"NAJEM/M1/2026",
         "amount":2500.00,"anchorDate":"2026-09-01"}
        """;

    @Autowired
    MockMvc mvc;

    @Test
    void seedsAScenarioAndReportsWhatItSeeded() throws Exception {
        mvc.perform(post("/api/scenarios").contentType(APPLICATION_JSON).content(SEED_BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.seeded").value(2))
            .andExpect(jsonPath("$.externalIds[0]").value("partial-then-topup/NAJEM-M1-2026/0"))
            .andExpect(jsonPath("$.externalIds[1]").value("partial-then-topup/NAJEM-M1-2026/1"));
    }

    @Test
    void seededLinesAreVisibleThroughTheExistingTransactionsEndpoint() throws Exception {
        mvc.perform(post("/api/scenarios").contentType(APPLICATION_JSON).content(SEED_BODY))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL61/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].title").value("NAJEM/M1/2026"))
            .andExpect(jsonPath("$[0].creditDebitIndicator").value("CRDT"));
    }

    @Test
    void rejectsAnUnknownScenarioName() throws Exception {
        mvc.perform(post("/api/scenarios").contentType(APPLICATION_JSON)
                .content("""
                    {"name":"nonsense","iban":"PL61","reference":"NAJEM/M1/2026",
                     "amount":2500.00,"anchorDate":"2026-09-01"}
                    """))
            .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :apps:fakebank:test --tests '*ScenarioControllerTest'`
Expected: FAIL — compilation error, `ScenarioController` does not exist.

- [ ] **Step 3: Write minimal implementation**

`ScenarioResult.java`:

```java
package pl.najem.fakebank;

import java.util.List;

/** What a seed call produced, so a test can name a specific line afterwards. */
public record ScenarioResult(int seeded, List<String> externalIds) {
}
```

`ScenarioController.java`:

```java
package pl.najem.fakebank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioCatalog catalog;
    private final TransactionStore store;

    public ScenarioController(ScenarioCatalog catalog, TransactionStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioResult seed(@RequestBody ScenarioRequest request) {
        List<BankTransactionDto> transactions = catalog.generate(request);
        store.addAll(request.iban(), transactions);
        return new ScenarioResult(transactions.size(),
            transactions.stream().map(BankTransactionDto::id).toList());
    }

    @ExceptionHandler(UnknownScenarioException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String unknownScenario(UnknownScenarioException exception) {
        return exception.getMessage();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :apps:fakebank:test`
Expected: PASS — all three controller tests plus everything from earlier tasks.

- [ ] **Step 5: Prove the whole repo is still green**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, `WalkingSkeletonTest` included.

- [ ] **Step 6: Commit**

```bash
git add apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioResult.java \
        apps/fakebank/src/main/java/pl/najem/fakebank/ScenarioController.java \
        apps/fakebank/src/test/java/pl/najem/fakebank/ScenarioControllerTest.java
git commit -m "Seed named scenarios over HTTP"
```

---

### Task 6: Scenario catalogue documentation

The consumers are other agents' tests. They need to know what each name means without reading the generator.

**Files:**
- Create: `apps/fakebank/README.md`

**Interfaces:**
- Consumes: the final scenario set from Task 4.
- Produces: no code.

- [ ] **Step 1: Write the document**

`apps/fakebank/README.md`:

````markdown
# FakeBank

A deterministic test-fixture engine that stands in for the landlord's bank. It is not a demo
sandbox: it exists so accounting's matching ladder and the e2e suite have reproducible bank data.
The same request always produces identical lines — no clock, no randomness, no persistence.

Runs standalone on port 8081: `./gradlew :apps:fakebank:bootRun`

## Seeding a scenario

```
POST /api/scenarios
{ "name": "partial-then-topup",
  "iban": "PL61109010140000071219812874",
  "reference": "NAJEM/M1/2026",
  "amount": "2500.00",
  "anchorDate": "2026-09-01" }

201 { "seeded": 2, "externalIds": ["partial-then-topup/NAJEM-M1-2026/0", "..."] }
```

`anchorDate` is the charge's due date; every scenario expresses its dates relative to it, so tests
never hardcode a calendar. An unknown `name` is a 400.

Seeded lines are then served by the existing endpoint, unchanged since Phase 0:
`GET /api/accounts/{iban}/transactions?since=YYYY-MM-DD`

## The scenarios

`A` = the requested amount, `D` = the anchor date.

| Name | Lines | What it exercises |
|---|---|---|
| `on-time` | A, exact reference, at D | Tier 1, the happy path |
| `late` | A at D+6, valued D+8 | Arrears timing; booking/value date divergence |
| `partial` | 60% of A at D | Partial allocation |
| `partial-then-topup` | 60% at D, remainder at D+4 | Many transfers, one charge |
| `overpay` | 120% of A at D | Overpayment handling |
| `wrong-reference` | A at D, title `najem m1 2026` | Tier 2 fuzzy reference matching |
| `no-reference` | A at D, empty title | Tiers 3–4, counterparty-based matching |
| `duplicate` | Two identical credits, distinct ids and bank references | Deduplication of a real bank duplicate |
| `reversal` | Credit at D, equal debit at D+3 | Returned transfer |
| `lump-sum` | 2×A at D, title naming two references | One transfer, many charges |
| `third-party-payer` | A at D, empty title, payer `ANNA KOWALSKA` on a stable non-tenant account | Tier 3 remembered-payer mapping |
| `outgoing-debit` | 287.43 DBIT at D+1, `OPLATA ZA MEDIA` | A line that must NOT become a rent payment |
| `foreign-currency` | A at D in EUR | Non-PLN as a classifiable fact |

## Field conventions

Amounts are **always positive**; direction is carried solely by `creditDebitIndicator`
(`CRDT` / `DBIT`, ISO 20022) and never duplicated as a sign.

`bankReference` is the bank's own reference and is deliberately unrelated to the tenant's payment
reference in `title` — telling those two apart is the point of the field.

`counterpartyIban` is a stable synthetic account: the same tenant keeps the same account across
scenarios and months, which is what makes the remembered-payer mapping testable. The third-party
payer has their own, equally stable, different account.

The first four fields (`id`, `amount`, `title`, `bookingDate`) are always present — that is the
Phase 0 shape. The other six are nullable and omitted from the JSON when null.

## Not here

MT940 export is Phase 2, landing together with its parser so producer and consumer are written
against each other rather than a guess.
````

- [ ] **Step 2: Commit**

```bash
git add apps/fakebank/README.md
git commit -m "Document the FakeBank scenario catalogue"
```

- [ ] **Step 3: Announce**

Rebase on latest main, run `./gradlew build`, and merge per the mechanic in force. Post `STATUS` to
`najem-build` with the commit hash, tagging `@najem-accounting` — their matching-ladder task 4 is
the consumer.

---

## Merge mechanics

The self-merge recipe in coordinator seq 30 (`git push . HEAD:main`) is rejected by git when the
main checkout has `main` checked out, which the coordinator-only rule guarantees. najem-contacts hit
this at seq 39 and proposed three fixes; the ruling is pending. Until it lands, finish each task's
commits on the branch and post `READY-FOR-REVIEW` rather than forcing anything — no `update-ref`,
no `branch -f`.

## Self-Review

**1. Spec coverage.** Every element of the agreed design has a task: the six nullable fields (Task 1),
the shared store the scenario endpoint needs (Task 2), all thirteen scenarios (Tasks 3–4), the HTTP
surface with its 400 (Task 5), the catalogue reference other agents read (Task 6). The three
scenarios accounting asked for at seq 33 — `third-party-payer`, `outgoing-debit`, `foreign-currency` —
are each covered by a named test in Task 4. MT940 is absent by ruling, and stated as absent in the
README so nobody looks for it.

**2. Placeholder scan.** No TBDs. Every code step carries the actual code; every test step carries
the actual assertions; every run step carries the exact command and the expected outcome.

**3. Type consistency.** `BankTransactionDto`'s ten-field canonical constructor defined in Task 1 is
what `ScenarioCatalog.line(...)` calls in Task 3 and what `ScenarioControllerTest` asserts on in
Task 5. `TransactionStore.addAll(String, List<BankTransactionDto>)` from Task 2 is the method
`ScenarioController` calls in Task 5. `ScenarioRequest`'s five components are used identically in
Tasks 3, 4 and 5, and match the JSON bodies in the controller test. `catalog.names()` returns
`Set<String>` in Task 3 and is asserted with `hasSize(13)` in Task 4, where the set is widened to
thirteen entries.

One deliberate departure from a first instinct, recorded so a reviewer does not "fix" it: the
`duplicate` scenario gives its two lines *different* external ids. A duplicate sharing one id would
be deduplicated by any storage keyed on id, which tests nothing — the case worth testing is the bank
booking the same payment twice under two of its own references.
