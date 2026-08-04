# MT940 Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce and consume MT940 bank statements, so accounting can ingest a real statement file the same way it ingests polled transactions, with the producer and parser written against each other rather than against a guess.

**Architecture:** A new leaf Gradle module `platform/mt940` holds a pure, Spring-free reader and writer that turn MT940 text into neutral records and back. FakeBank gains an export endpoint that renders its seeded scenarios as MT940. The accounting side gains a small upload path that parses text and feeds `IngestionService.ingest(BankLine)` — the seam that already exists — rather than implementing `BankStatementPort`, which is a pull abstraction that an uploaded file cannot honestly satisfy. The 13 existing scenarios are the shared fixture: a round-trip test asserts that parsing scenario X's MT940 export yields the same transactions the JSON endpoint serves for scenario X.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5 + AssertJ. `platform/mt940` has **no** dependencies — not Spring, not Jackson, not `contracts`, not `platform/eventstore`. Only the JDK.

## Global Constraints

- `platform/mt940` is a pure JVM library: no Spring, no dependency on `platform/eventstore`, `contracts`, or any module. It parses text into neutral records, full stop. (Coordinator ruling, topic seq 85.)
- Self-merge is permitted within `platform/mt940/**` and `apps/fakebank/**`. The one root touch — the `settings.gradle.kts` include line — is allowed as part of Task 1's commit, that line only.
- **Explicit-path staging only.** Never `git add -A`. Stage each file by name.
- Commits are signed as the repo user. No AI/LLM mention in any commit message.
- TDD, red first, and the red must be *verified by running it* — a test that fails to compile counts as red only if you ran it and saw the compile error.
- Amounts are **always positive**; direction is carried solely by the credit/debit mark and never duplicated as a sign. (Convention established in Phase 1, `apps/fakebank/README.md`; accounting has committed that nothing reads `signum()`.)
- Every task ends with a full `./gradlew build` green before its commit, after rebasing onto `main`.
- Build environment on this machine, required per shell:
  ```sh
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  export DOCKER_HOST="unix:///Users/jaca/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
  ```
- Test counts are read from the JUnit XML under `build/test-results/test/`, not from Gradle's console summary.

## Open Decisions (accounting-owned, do not decide unilaterally)

Tasks 1–6 do not depend on these. **Task 7 is gated on them.** Raised on topic seq 86; record the answers here when they arrive.

1. **`BankLine` width.** Today it is `(externalId, amount, title, bookingDate)` — no counterparty, no indicator, no currency. Ladder tiers 3–4 are counterparty-based and cannot be implemented against it. If accounting widens it, Task 7's mapping carries the extra fields; if not, Task 7 drops them. Either way Tasks 1–6 are unaffected. **Do not modify `BankLine` in this plan.**
2. **`externalId` derivation for an uploaded line.** Proposed: `"mt940/" + account + "/" + statementNumber + "/" + index`. Stable under re-upload (dedup holds), distinct for genuine same-day duplicates. Accounting owns the dedup guarantee and may replace this.
3. **Who writes the accounting-side file** — this agent with per-file consent, or accounting themselves against the reader. Task 7 assumes the former; if the latter, Task 7 becomes a handoff of the reader plus the round-trip fixture.

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | one added include line: `"platform:mt940"` |
| `platform/mt940/build.gradle.kts` | `java-library`, no dependencies beyond the root's test defaults |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940Mark.java` | `C` / `D` — the credit-debit mark, the only carrier of direction |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940Line.java` | one `:61:`+`:86:` pair as a neutral record |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940Statement.java` | account, statement number, currency, lines |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940FormatException.java` | every rejection this library makes, with the offending text |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940Tags.java` | splits raw text into `(tag, value)` fields, handling continuation lines |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940Reader.java` | text → `List<Mt940Statement>` |
| `platform/mt940/src/main/java/pl/najem/mt940/Mt940Writer.java` | `Mt940Statement` → text |
| `platform/mt940/src/test/java/pl/najem/mt940/Mt940TagsTest.java` | field splitting and continuation lines |
| `platform/mt940/src/test/java/pl/najem/mt940/Mt940ReaderTest.java` | the substantive parsing tests |
| `platform/mt940/src/test/java/pl/najem/mt940/Mt940WriterTest.java` | rendering + writer/reader round trip |
| `apps/fakebank/build.gradle.kts` | add `implementation(project(":platform:mt940"))` |
| `apps/fakebank/src/main/java/pl/najem/fakebank/StatementRenderer.java` | seeded `BankTransactionDto`s → `Mt940Statement`s, grouped by currency |
| `apps/fakebank/src/main/java/pl/najem/fakebank/StatementController.java` | `GET /api/accounts/{iban}/statement.mt940` |
| `apps/fakebank/src/test/java/pl/najem/fakebank/StatementRendererTest.java` | pure rendering tests |
| `apps/fakebank/src/test/java/pl/najem/fakebank/StatementRoundTripTest.java` | **the point of the whole plan:** export → parse → compare against the JSON shape, for all 13 scenarios |
| `apps/fakebank/README.md` | replace the "Not here / MT940 is Phase 2" section with the export's documentation |

`Mt940Tags` is split out from `Mt940Reader` deliberately: lexing (where does a field start and end) and parsing (what does `:61:` mean) fail differently and are worth testing apart. Everything else is one responsibility per file.

---

### Task 1: The module and its records

Nothing parses yet. This task exists because a new Gradle module that doesn't compile is a bad thing to discover in the middle of a parsing task.

**Files:**
- Modify: `settings.gradle.kts` (one line)
- Create: `platform/mt940/build.gradle.kts`
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940Mark.java`
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940Line.java`
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940Statement.java`
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940FormatException.java`
- Test: `platform/mt940/src/test/java/pl/najem/mt940/Mt940LineTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Mt940Mark {C, D}`; `Mt940Line(LocalDate bookingDate, LocalDate valueDate, BigDecimal amount, Mt940Mark mark, String bankReference, String customerReference, String remittanceInfo, String counterpartyName, String counterpartyIban)`; `Mt940Statement(String account, String statementNumber, String currency, List<Mt940Line> lines)`; `Mt940FormatException(String message)`. Every later task uses these names verbatim.

- [ ] **Step 1: Write the failing test**

`platform/mt940/src/test/java/pl/najem/mt940/Mt940LineTest.java`:

```java
package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940LineTest {

    @Test
    void rejectsANegativeAmountBecauseDirectionLivesInTheMark() {
        assertThatThrownBy(() -> new Mt940Line(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), new BigDecimal("-1.00"),
                Mt940Mark.C, "BNP1", "NONREF", "x", null, null))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("-1.00");
    }

    @Test
    void keepsTheMarkAsTheOnlyStatementOfDirection() {
        Mt940Line debit = new Mt940Line(
            LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 4), new BigDecimal("287.43"),
            Mt940Mark.D, "BNP2", "NONREF", "OPLATA ZA MEDIA", "PGNIG", "PL10");

        assertThat(debit.amount()).isEqualByComparingTo("287.43");
        assertThat(debit.mark()).isEqualTo(Mt940Mark.D);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940LineTest'
```

Expected: FAIL — the project `:platform:mt940` does not exist. That is the red for the module itself. Add the include line and the build file (Step 3), re-run, and expect a second red: compile errors, `Mt940Line` not found.

- [ ] **Step 3: Write minimal implementation**

`settings.gradle.kts` — add `"platform:mt940",` immediately after `"platform:eventstore",`.

`platform/mt940/build.gradle.kts`:

```kotlin
plugins { `java-library` }

// Deliberately dependency-free: this library parses text into records and knows nothing else.
// Test dependencies (JUnit, AssertJ) come from the root build's subprojects block.
```

`Mt940Mark.java`:

```java
package pl.najem.mt940;

/** The credit-debit mark from :61:. The sole carrier of direction — amounts are never signed. */
public enum Mt940Mark {
    C, D
}
```

`Mt940FormatException.java`:

```java
package pl.najem.mt940;

/** Every rejection this library makes. The message always quotes the text that caused it. */
public class Mt940FormatException extends RuntimeException {

    public Mt940FormatException(String message) {
        super(message);
    }
}
```

`Mt940Line.java`:

```java
package pl.najem.mt940;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One statement line: a :61: entry with the :86: information that follows it.
 *
 * <p>{@code counterpartyName} and {@code counterpartyIban} are null when the statement carries an
 * unstructured :86:, which is normal and not an error — many banks send free text.
 */
public record Mt940Line(LocalDate bookingDate, LocalDate valueDate, BigDecimal amount, Mt940Mark mark,
                        String bankReference, String customerReference, String remittanceInfo,
                        String counterpartyName, String counterpartyIban) {

    public Mt940Line {
        if (amount == null || amount.signum() < 0) {
            throw new Mt940FormatException("Amount must be present and non-negative, was: " + amount);
        }
    }
}
```

`Mt940Statement.java`:

```java
package pl.najem.mt940;

import java.util.List;

/**
 * One :20:…:62F: statement block.
 *
 * <p>A statement carries exactly one currency — MT940 states it on the balance fields, not per
 * line — so transactions in two currencies are two statements, never one.
 */
public record Mt940Statement(String account, String statementNumber, String currency, List<Mt940Line> lines) {

    public Mt940Statement {
        lines = List.copyOf(lines);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940LineTest'
```

Expected: PASS, 2 tests.

- [ ] **Step 5: Full build, then commit**

```sh
./gradlew build
git add settings.gradle.kts platform/mt940/build.gradle.kts \
  platform/mt940/src/main/java/pl/najem/mt940/Mt940Mark.java \
  platform/mt940/src/main/java/pl/najem/mt940/Mt940Line.java \
  platform/mt940/src/main/java/pl/najem/mt940/Mt940Statement.java \
  platform/mt940/src/main/java/pl/najem/mt940/Mt940FormatException.java \
  platform/mt940/src/test/java/pl/najem/mt940/Mt940LineTest.java
git commit -m "Add an MT940 library module with its statement records"
```

---

### Task 2: Field splitting

**Files:**
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940Tags.java`
- Test: `platform/mt940/src/test/java/pl/najem/mt940/Mt940TagsTest.java`

**Interfaces:**
- Consumes: `Mt940FormatException`.
- Produces: `Mt940Tags.Field(String tag, String value)` and `static List<Field> split(String text)`. Task 3 consumes both.

Continuation lines are the reason this is its own task: a `:86:` may run over several physical lines, and a line that does not begin with `:nn:` belongs to the field above it. Get that wrong and remittance text is silently truncated — which looks like a bank sending short references rather than like a bug.

- [ ] **Step 1: Write the failing test**

`Mt940TagsTest.java`:

```java
package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940TagsTest {

    @Test
    void splitsFieldsOnTheirTags() {
        List<Mt940Tags.Field> fields = Mt940Tags.split("""
            :20:NAJEM1
            :25:PL61109010140000071219812874
            :28C:1/1
            """);

        assertThat(fields).containsExactly(
            new Mt940Tags.Field("20", "NAJEM1"),
            new Mt940Tags.Field("25", "PL61109010140000071219812874"),
            new Mt940Tags.Field("28C", "1/1"));
    }

    @Test
    void joinsContinuationLinesIntoTheFieldAbove() {
        List<Mt940Tags.Field> fields = Mt940Tags.split("""
            :86:~20NAJEM/M1/2026
            ~32NAJEMCA NAJEM-M1-2026
            :62F:C260901PLN2500,00
            """);

        assertThat(fields).hasSize(2);
        assertThat(fields.getFirst().value()).isEqualTo("~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026");
    }

    @Test
    void toleratesCarriageReturnsAndTheTrailingBlockTerminator() {
        List<Mt940Tags.Field> fields = Mt940Tags.split(":20:NAJEM1\r\n:25:PL61\r\n-\r\n");

        assertThat(fields).containsExactly(
            new Mt940Tags.Field("20", "NAJEM1"),
            new Mt940Tags.Field("25", "PL61"));
    }

    @Test
    void rejectsTextThatDoesNotStartWithATag() {
        assertThatThrownBy(() -> Mt940Tags.split("NAJEM1\n:25:PL61\n"))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("NAJEM1");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940TagsTest'
```

Expected: FAIL — compile error, `Mt940Tags` not found.

- [ ] **Step 3: Write minimal implementation**

`Mt940Tags.java`:

```java
package pl.najem.mt940;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits MT940 text into its fields.
 *
 * <p>Lexing only — this class knows that a field starts with {@code :nn:} and that anything else
 * continues the field above. It does not know what any tag means.
 */
public final class Mt940Tags {

    public record Field(String tag, String value) {}

    /** A tag is two digits and an optional letter: :20:, :28C:, :60F:, :61:, :86:. */
    private static final Pattern TAG = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");

    /** End-of-block marker; a line of a single hyphen. */
    private static final String BLOCK_TERMINATOR = "-";

    private Mt940Tags() {
    }

    public static List<Field> split(String text) {
        List<String> tags = new ArrayList<>();
        List<StringBuilder> values = new ArrayList<>();
        for (String line : text.split("\r?\n")) {
            if (line.isBlank() || line.strip().equals(BLOCK_TERMINATOR)) {
                continue;
            }
            Matcher matcher = TAG.matcher(line.strip());
            if (matcher.matches()) {
                tags.add(matcher.group(1));
                values.add(new StringBuilder(matcher.group(2)));
            } else if (values.isEmpty()) {
                throw new Mt940FormatException("Expected a field to start the statement, found: " + line);
            } else {
                values.getLast().append(line.strip());
            }
        }
        List<Field> fields = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            fields.add(new Field(tags.get(i), values.get(i).toString()));
        }
        return fields;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940TagsTest'
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```sh
./gradlew build
git add platform/mt940/src/main/java/pl/najem/mt940/Mt940Tags.java \
  platform/mt940/src/test/java/pl/najem/mt940/Mt940TagsTest.java
git commit -m "Split MT940 text into fields, joining continuation lines"
```

---

### Task 3: The reader

**Files:**
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940Reader.java`
- Test: `platform/mt940/src/test/java/pl/najem/mt940/Mt940ReaderTest.java`

**Interfaces:**
- Consumes: `Mt940Tags.split`, all records from Task 1.
- Produces: `Mt940Reader.read(String text) -> List<Mt940Statement>` (static). Tasks 6 and 7 call it.

The `:61:` grammar this implements, which is the subset every Polish bank emits:

```
:61:YYMMDD[MMDD]{C|D|RC|RD}[fundsCode]amount<type>customerRef[//bankRef]
     ^value  ^booking ^mark  ^1 alpha   ^comma  ^4 chars  ^≤16
```

`:86:` comes in two shapes and both are normal:
- **structured** — `~20`…`~29` remittance, `~32`/`~33` counterparty name, `~38` counterparty IBAN
- **unstructured** — free text, which becomes the remittance and leaves the counterparty fields null

Booking date carries no year. It is inferred from the value date's year, adjusted by one when the two straddle New Year — a statement issued on 2 January carries December bookings, and without the adjustment they land twelve months in the future.

- [ ] **Step 1: Write the failing test**

`Mt940ReaderTest.java`:

```java
package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mt940ReaderTest {

    private static final String ON_TIME = """
        :20:NAJEM1
        :25:PL61109010140000071219812874
        :28C:1/1
        :60F:C260901PLN0,00
        :61:2609010901C2500,00NTRFNONREF//BNP00123456
        :86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL00000000000000000123456789
        :62F:C260901PLN2500,00
        -
        """;

    @Test
    void readsTheStatementHeader() {
        Mt940Statement statement = Mt940Reader.read(ON_TIME).getFirst();

        assertThat(statement.account()).isEqualTo("PL61109010140000071219812874");
        assertThat(statement.statementNumber()).isEqualTo("1/1");
        assertThat(statement.currency()).isEqualTo("PLN");
    }

    @Test
    void readsACreditLineWithoutEverSigningTheAmount() {
        Mt940Line line = Mt940Reader.read(ON_TIME).getFirst().lines().getFirst();

        assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(line.bookingDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(line.amount()).isEqualByComparingTo("2500.00");
        assertThat(line.mark()).isEqualTo(Mt940Mark.C);
        assertThat(line.bankReference()).isEqualTo("BNP00123456");
        assertThat(line.customerReference()).isEqualTo("NONREF");
    }

    @Test
    void readsAStructured86IntoRemittanceAndCounterparty() {
        Mt940Line line = Mt940Reader.read(ON_TIME).getFirst().lines().getFirst();

        assertThat(line.remittanceInfo()).isEqualTo("NAJEM/M1/2026");
        assertThat(line.counterpartyName()).isEqualTo("NAJEMCA NAJEM-M1-2026");
        assertThat(line.counterpartyIban()).isEqualTo("PL00000000000000000123456789");
    }

    @Test
    void readsADebitAsAPositiveAmountWithADebitMark() {
        String text = ON_TIME.replace("C2500,00NTRFNONREF", "D287,43NTRFNONREF");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.amount()).isEqualByComparingTo("287.43");
        assertThat(line.mark()).isEqualTo(Mt940Mark.D);
    }

    @Test
    void treatsAReversalMarkAsItsUnderlyingDirection() {
        String text = ON_TIME.replace("C2500,00NTRF", "RC2500,00NTRF");

        assertThat(Mt940Reader.read(text).getFirst().lines().getFirst().mark()).isEqualTo(Mt940Mark.C);
    }

    @Test
    void keepsUnstructured86AsRemittanceAndLeavesCounterpartyUnknown() {
        String text = ON_TIME.replace(
            ":86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL00000000000000000123456789",
            ":86:PRZELEW NAJEM M1 2026");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.remittanceInfo()).isEqualTo("PRZELEW NAJEM M1 2026");
        assertThat(line.counterpartyName()).isNull();
        assertThat(line.counterpartyIban()).isNull();
    }

    @Test
    void joinsRemittanceSubfieldsInOrder() {
        String text = ON_TIME.replace("~20NAJEM/M1/2026~32", "~20NAJEM~21/M1/2026~32");

        assertThat(Mt940Reader.read(text).getFirst().lines().getFirst().remittanceInfo())
            .isEqualTo("NAJEM/M1/2026");
    }

    @Test
    void readsALineWhoseInformationFieldIsAbsent() {
        String text = ON_TIME.replace(
            ":86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL00000000000000000123456789\n", "");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.remittanceInfo()).isEmpty();
        assertThat(line.counterpartyName()).isNull();
    }

    @Test
    void infersTheBookingYearBackwardsWhenAStatementStraddlesNewYear() {
        String text = ON_TIME
            .replace(":61:2609010901C", ":61:2601021230C")
            .replace(":60F:C260901PLN", ":60F:C260102PLN")
            .replace(":62F:C260901PLN", ":62F:C260102PLN");

        Mt940Line line = Mt940Reader.read(text).getFirst().lines().getFirst();

        assertThat(line.valueDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(line.bookingDate()).isEqualTo(LocalDate.of(2025, 12, 30));
    }

    @Test
    void readsSeveralStatementsFromOneFile() {
        List<Mt940Statement> statements = Mt940Reader.read(ON_TIME + ON_TIME.replace(":28C:1/1", ":28C:2/1"));

        assertThat(statements).hasSize(2);
        assertThat(statements.get(1).statementNumber()).isEqualTo("2/1");
    }

    @Test
    void rejectsAnAmountItCannotRead() {
        String text = ON_TIME.replace("C2500,00NTRF", "Ctwo-thousandNTRF");

        assertThatThrownBy(() -> Mt940Reader.read(text))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining("two-thousand");
    }

    @Test
    void rejectsAStatementWithNoAccount() {
        String text = ON_TIME.replace(":25:PL61109010140000071219812874\n", "");

        assertThatThrownBy(() -> Mt940Reader.read(text))
            .isInstanceOf(Mt940FormatException.class)
            .hasMessageContaining(":25:");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940ReaderTest'
```

Expected: FAIL — compile error, `Mt940Reader` not found.

- [ ] **Step 3: Write minimal implementation**

`Mt940Reader.java`:

```java
package pl.najem.mt940;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads MT940 text into statements. Pure: no state, no clock, no I/O. */
public final class Mt940Reader {

    /** :61:  value date, optional booking MMDD, mark, optional funds code, amount, type, refs. */
    private static final Pattern ENTRY = Pattern.compile(
        "^(\\d{6})(\\d{4})?(RC|RD|C|D)([A-Z])?([\\d,]+)([A-Z][A-Z0-9]{3})(.*)$");

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    /** :86: subfield: a tilde, two digits, then everything up to the next tilde. */
    private static final Pattern SUBFIELD = Pattern.compile("~(\\d{2})([^~]*)");

    private Mt940Reader() {
    }

    public static List<Mt940Statement> read(String text) {
        List<Mt940Statement> statements = new ArrayList<>();
        List<Mt940Tags.Field> fields = Mt940Tags.split(text);

        String account = null;
        String number = null;
        String currency = null;
        List<Mt940Line> lines = new ArrayList<>();
        Mt940Line pending = null;

        for (Mt940Tags.Field field : fields) {
            switch (field.tag()) {
                case "20" -> {
                    if (account != null || pending != null || !lines.isEmpty()) {
                        pending = flush(statements, account, number, currency, lines, pending);
                        account = null;
                        number = null;
                        currency = null;
                        lines = new ArrayList<>();
                    }
                }
                case "25" -> account = field.value();
                case "28C", "28" -> number = field.value();
                case "60F", "60M" -> currency = balanceCurrency(field.value());
                case "61" -> {
                    if (pending != null) {
                        lines.add(pending);
                    }
                    pending = entry(field.value());
                }
                case "86" -> {
                    if (pending != null) {
                        lines.add(information(pending, field.value()));
                        pending = null;
                    }
                }
                default -> {
                    // :62F:, :64:, :65:, :13D: and friends carry nothing this library exposes.
                }
            }
        }
        flush(statements, account, number, currency, lines, pending);
        return statements;
    }

    private static Mt940Line flush(List<Mt940Statement> statements, String account, String number,
                                   String currency, List<Mt940Line> lines, Mt940Line pending) {
        if (pending != null) {
            lines.add(pending);
        }
        if (account == null) {
            throw new Mt940FormatException("Statement has no account; :25: is required");
        }
        statements.add(new Mt940Statement(account, number, currency, lines));
        return null;
    }

    private static Mt940Line entry(String value) {
        Matcher matcher = ENTRY.matcher(value);
        if (!matcher.matches()) {
            throw new Mt940FormatException("Unreadable :61: entry: " + value);
        }
        LocalDate valueDate = date(matcher.group(1));
        LocalDate bookingDate = bookingDate(valueDate, matcher.group(2));
        BigDecimal amount = amount(matcher.group(5));
        Mt940Mark mark = matcher.group(3).endsWith("C") ? Mt940Mark.C : Mt940Mark.D;

        String references = matcher.group(7);
        int separator = references.indexOf("//");
        String customerReference = (separator < 0 ? references : references.substring(0, separator)).strip();
        String bankReference = separator < 0 ? null : references.substring(separator + 2).strip();

        return new Mt940Line(bookingDate, valueDate, amount, mark, bankReference, customerReference, "", null, null);
    }

    private static Mt940Line information(Mt940Line line, String value) {
        if (!value.contains("~")) {
            return withInformation(line, value.strip(), null, null);
        }
        StringBuilder remittance = new StringBuilder();
        String name = null;
        String iban = null;
        Matcher matcher = SUBFIELD.matcher(value);
        while (matcher.find()) {
            int subfield = Integer.parseInt(matcher.group(1));
            String content = matcher.group(2);
            if (subfield >= 20 && subfield <= 29) {
                remittance.append(content);
            } else if (subfield == 32 || subfield == 33) {
                name = name == null ? content : name + content;
            } else if (subfield == 38) {
                iban = content;
            }
        }
        return withInformation(line, remittance.toString().strip(), blankToNull(name), blankToNull(iban));
    }

    private static Mt940Line withInformation(Mt940Line line, String remittance, String name, String iban) {
        return new Mt940Line(line.bookingDate(), line.valueDate(), line.amount(), line.mark(),
            line.bankReference(), line.customerReference(), remittance, name, iban);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDate date(String yymmdd) {
        try {
            return LocalDate.parse(yymmdd, YYMMDD);
        } catch (DateTimeParseException e) {
            throw new Mt940FormatException("Unreadable date: " + yymmdd);
        }
    }

    /**
     * The booking date carries no year. It is the value date's year, moved by one when the two fall
     * on opposite sides of New Year — a January statement carries December bookings.
     */
    private static LocalDate bookingDate(LocalDate valueDate, String mmdd) {
        if (mmdd == null) {
            return valueDate;
        }
        int month = Integer.parseInt(mmdd.substring(0, 2));
        int day = Integer.parseInt(mmdd.substring(2, 4));
        LocalDate candidate = LocalDate.of(valueDate.getYear(), month, day);
        if (candidate.isAfter(valueDate.plusMonths(6))) {
            return candidate.minusYears(1);
        }
        if (candidate.isBefore(valueDate.minusMonths(6))) {
            return candidate.plusYears(1);
        }
        return candidate;
    }

    private static BigDecimal amount(String text) {
        try {
            return new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new Mt940FormatException("Unreadable amount: " + text);
        }
    }

    /** :60F: is mark + YYMMDD + currency + amount; only the currency matters here. */
    private static String balanceCurrency(String value) {
        if (value.length() < 10) {
            throw new Mt940FormatException("Unreadable opening balance: " + value);
        }
        return value.substring(7, 10);
    }
}
```

Note on `rejectsAnAmountItCannotRead`: `Ctwo-thousandNTRF` fails the `ENTRY` pattern as a whole, so the message quotes the entry, which contains `two-thousand`. The assertion holds either way.

- [ ] **Step 4: Run test to verify it passes**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940ReaderTest'
```

Expected: PASS, 12 tests.

- [ ] **Step 5: Commit**

```sh
./gradlew build
git add platform/mt940/src/main/java/pl/najem/mt940/Mt940Reader.java \
  platform/mt940/src/test/java/pl/najem/mt940/Mt940ReaderTest.java
git commit -m "Read MT940 statements, entries and information fields"
```

---

### Task 4: The writer

**Files:**
- Create: `platform/mt940/src/main/java/pl/najem/mt940/Mt940Writer.java`
- Test: `platform/mt940/src/test/java/pl/najem/mt940/Mt940WriterTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: `Mt940Writer.write(List<Mt940Statement>) -> String` and `Mt940Writer.write(Mt940Statement) -> String` (static). Task 5 calls it.

The writer's closing balance is computed from the lines — credits add, debits subtract — because a statement whose balance disagrees with its own entries is the one thing a bank never sends and a reconciliation screen would rightly alarm on.

- [ ] **Step 1: Write the failing test**

`Mt940WriterTest.java`:

```java
package pl.najem.mt940;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Mt940WriterTest {

    private static Mt940Line credit(String amount, String remittance) {
        return new Mt940Line(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), new BigDecimal(amount),
            Mt940Mark.C, "BNP00123456", "NONREF", remittance, "NAJEMCA NAJEM-M1-2026", "PL99");
    }

    @Test
    void writesTheTagsABankWouldSend() {
        String text = Mt940Writer.write(new Mt940Statement("PL61", "1/1", "PLN", List.of(credit("2500.00", "NAJEM/M1/2026"))));

        assertThat(text.lines()).contains(
            ":25:PL61",
            ":28C:1/1",
            ":60F:C260901PLN0,00",
            ":61:2609010901C2500,00NTRFNONREF//BNP00123456",
            ":86:~20NAJEM/M1/2026~32NAJEMCA NAJEM-M1-2026~38PL99",
            ":62F:C260901PLN2500,00",
            "-");
    }

    @Test
    void subtractsDebitsFromTheClosingBalance() {
        Mt940Line debit = new Mt940Line(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2),
            new BigDecimal("287.43"), Mt940Mark.D, "BNP2", "NONREF", "OPLATA ZA MEDIA", "PGNIG", "PL10");

        String text = Mt940Writer.write(new Mt940Statement("PL61", "1/1", "PLN", List.of(credit("2500.00", "x"), debit)));

        assertThat(text.lines()).contains(":62F:C260902PLN2212,57");
    }

    @Test
    void writesANegativeClosingBalanceAsADebitMarkNotAMinusSign() {
        Mt940Line debit = new Mt940Line(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2),
            new BigDecimal("100.00"), Mt940Mark.D, "BNP2", "NONREF", "x", null, null);

        String text = Mt940Writer.write(new Mt940Statement("PL61", "1/1", "PLN", List.of(debit)));

        assertThat(text.lines()).contains(":62F:D260902PLN100,00");
    }

    @Test
    void omitsCounterpartySubfieldsWhenTheyAreUnknown() {
        Mt940Line anonymous = new Mt940Line(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
            new BigDecimal("10.00"), Mt940Mark.C, "BNP3", "NONREF", "ANON", null, null);

        String text = Mt940Writer.write(new Mt940Statement("PL61", "1/1", "PLN", List.of(anonymous)));

        assertThat(text.lines()).contains(":86:~20ANON");
    }

    @Test
    void roundTripsEveryFieldThroughTheReader() {
        Mt940Statement original = new Mt940Statement("PL61", "1/1", "PLN",
            List.of(credit("2500.00", "NAJEM/M1/2026")));

        Mt940Statement reread = Mt940Reader.read(Mt940Writer.write(original)).getFirst();

        assertThat(reread).isEqualTo(original);
    }

    @Test
    void roundTripsSeveralStatementsInOneFile() {
        Mt940Statement pln = new Mt940Statement("PL61", "1/1", "PLN", List.of(credit("2500.00", "A")));
        Mt940Statement eur = new Mt940Statement("PL61", "2/1", "EUR", List.of(credit("600.00", "B")));

        List<Mt940Statement> reread = Mt940Reader.read(Mt940Writer.write(List.of(pln, eur)));

        assertThat(reread).containsExactly(pln, eur);
    }
}
```

`roundTripsEveryFieldThroughTheReader` is the test that keeps the two halves honest: any field the writer emits and the reader ignores, or vice versa, fails here rather than in an integration somewhere.

- [ ] **Step 2: Run test to verify it fails**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940WriterTest'
```

Expected: FAIL — compile error, `Mt940Writer` not found.

- [ ] **Step 3: Write minimal implementation**

`Mt940Writer.java`:

```java
package pl.najem.mt940;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Writes statements as the MT940 text a bank would send. Pure: no state, no clock, no I/O. */
public final class Mt940Writer {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");

    private Mt940Writer() {
    }

    public static String write(List<Mt940Statement> statements) {
        StringBuilder text = new StringBuilder();
        for (Mt940Statement statement : statements) {
            text.append(write(statement));
        }
        return text.toString();
    }

    public static String write(Mt940Statement statement) {
        LocalDate opening = statement.lines().isEmpty()
            ? LocalDate.EPOCH : statement.lines().getFirst().valueDate();
        LocalDate closing = statement.lines().isEmpty()
            ? opening : statement.lines().getLast().valueDate();

        StringBuilder text = new StringBuilder()
            .append(":20:NAJEM").append(digitsOf(statement.statementNumber())).append('\n')
            .append(":25:").append(statement.account()).append('\n')
            .append(":28C:").append(statement.statementNumber()).append('\n')
            .append(":60F:").append(balance(BigDecimal.ZERO, opening, statement.currency())).append('\n');

        for (Mt940Line line : statement.lines()) {
            text.append(entry(line)).append('\n').append(information(line)).append('\n');
        }
        return text
            .append(":62F:").append(balance(total(statement), closing, statement.currency())).append('\n')
            .append("-\n")
            .toString();
    }

    private static String entry(Mt940Line line) {
        StringBuilder entry = new StringBuilder(":61:")
            .append(YYMMDD.format(line.valueDate()))
            .append(MMDD.format(line.bookingDate()))
            .append(line.mark())
            .append(amount(line.amount()))
            .append("NTRF")
            .append(line.customerReference());
        if (line.bankReference() != null) {
            entry.append("//").append(line.bankReference());
        }
        return entry.toString();
    }

    private static String information(Mt940Line line) {
        StringBuilder information = new StringBuilder(":86:~20").append(line.remittanceInfo());
        if (line.counterpartyName() != null) {
            information.append("~32").append(line.counterpartyName());
        }
        if (line.counterpartyIban() != null) {
            information.append("~38").append(line.counterpartyIban());
        }
        return information.toString();
    }

    /** Credits add, debits subtract. A statement must agree with its own entries. */
    private static BigDecimal total(Mt940Statement statement) {
        BigDecimal total = BigDecimal.ZERO;
        for (Mt940Line line : statement.lines()) {
            total = line.mark() == Mt940Mark.C ? total.add(line.amount()) : total.subtract(line.amount());
        }
        return total;
    }

    /** A balance states its direction with a mark, exactly as an entry does — never with a sign. */
    private static String balance(BigDecimal total, LocalDate date, String currency) {
        char mark = total.signum() < 0 ? 'D' : 'C';
        return mark + YYMMDD.format(date) + currency + amount(total.abs());
    }

    private static String amount(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString().replace('.', ',');
    }

    /** :20: is a free transaction reference; deriving it from the statement number keeps it stable. */
    private static String digitsOf(String statementNumber) {
        return statementNumber == null ? "1" : statementNumber.replaceAll("\\D", "");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```sh
./gradlew :platform:mt940:test --tests 'pl.najem.mt940.Mt940WriterTest'
```

Expected: PASS, 6 tests. Then the whole module:

```sh
./gradlew :platform:mt940:test
```

Expected: PASS, 24 tests (2 + 4 + 12 + 6).

- [ ] **Step 5: Commit**

```sh
./gradlew build
git add platform/mt940/src/main/java/pl/najem/mt940/Mt940Writer.java \
  platform/mt940/src/test/java/pl/najem/mt940/Mt940WriterTest.java
git commit -m "Write statements as MT940 text and round-trip them through the reader"
```

---

### Task 5: FakeBank exports its seeded lines as MT940

**Files:**
- Modify: `apps/fakebank/build.gradle.kts`
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/StatementRenderer.java`
- Create: `apps/fakebank/src/main/java/pl/najem/fakebank/StatementController.java`
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/StatementRendererTest.java`

**Interfaces:**
- Consumes: `TransactionStore.find(String iban, LocalDate since)`, `BankTransactionDto`, `Mt940Writer.write`.
- Produces: `StatementRenderer.render(String iban, List<BankTransactionDto>) -> List<Mt940Statement>`; `GET /api/accounts/{iban}/statement.mt940?since=` returning `text/plain`. Task 6 calls both.

One statement per currency, currencies in alphabetical order, statement numbers `1/1`, `2/1`, … in that order. That ordering is not cosmetic: it is what makes the export deterministic, which is the property the whole FakeBank exists to provide. MT940 states currency once per statement, on the balance fields, so a `foreign-currency` seed genuinely cannot share a statement with a PLN one.

- [ ] **Step 1: Write the failing test**

`StatementRendererTest.java`:

```java
package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Statement;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        List<BankTransactionDto> mixed = new java.util.ArrayList<>(seed("on-time"));
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
```

`keepsTheTwoLinesOfADuplicateApart` guards the Phase 1 decision that a duplicate's two lines are deliberately distinguishable. A renderer that collapsed them would make the scenario test nothing.

- [ ] **Step 2: Run test to verify it fails**

```sh
./gradlew :apps:fakebank:test --tests 'pl.najem.fakebank.StatementRendererTest'
```

Expected: FAIL — compile error, `StatementRenderer` and the `pl.najem.mt940` imports not found.

- [ ] **Step 3: Write minimal implementation**

`apps/fakebank/build.gradle.kts` — add to `dependencies`:

```kotlin
    implementation(project(":platform:mt940"))
```

`StatementRenderer.java`:

```java
package pl.najem.fakebank;

import org.springframework.stereotype.Component;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders seeded transactions as MT940 statements.
 *
 * <p>One statement per currency, currencies in alphabetical order: MT940 states the currency once,
 * on the balance fields, so transactions in two currencies cannot share a statement. The ordering is
 * what keeps the export deterministic.
 */
@Component
public class StatementRenderer {

    private static final String NO_CUSTOMER_REFERENCE = "NONREF";

    public List<Mt940Statement> render(String iban, List<BankTransactionDto> transactions) {
        Map<String, List<Mt940Line>> byCurrency = new TreeMap<>();
        for (BankTransactionDto transaction : transactions) {
            byCurrency.computeIfAbsent(currencyOf(transaction), currency -> new ArrayList<>())
                .add(line(transaction));
        }
        List<Mt940Statement> statements = new ArrayList<>(byCurrency.size());
        int number = 1;
        for (Map.Entry<String, List<Mt940Line>> entry : byCurrency.entrySet()) {
            statements.add(new Mt940Statement(iban, number++ + "/1", entry.getKey(), entry.getValue()));
        }
        return statements;
    }

    private Mt940Line line(BankTransactionDto transaction) {
        return new Mt940Line(
            transaction.bookingDate(),
            transaction.valueDate() == null ? transaction.bookingDate() : transaction.valueDate(),
            transaction.amount(),
            "DBIT".equals(transaction.creditDebitIndicator()) ? Mt940Mark.D : Mt940Mark.C,
            transaction.bankReference(),
            NO_CUSTOMER_REFERENCE,
            transaction.title() == null ? "" : transaction.title(),
            transaction.counterpartyName(),
            transaction.counterpartyIban());
    }

    private static String currencyOf(BankTransactionDto transaction) {
        return transaction.currency() == null ? "PLN" : transaction.currency();
    }
}
```

`StatementController.java`:

```java
package pl.najem.fakebank;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.mt940.Mt940Writer;

import java.time.LocalDate;

/** The same seeded lines the JSON endpoint serves, in the format a bank would hand over. */
@RestController
public class StatementController {

    private final TransactionStore store;
    private final StatementRenderer renderer;

    public StatementController(TransactionStore store, StatementRenderer renderer) {
        this.store = store;
        this.renderer = renderer;
    }

    @GetMapping(value = "/api/accounts/{iban}/statement.mt940", produces = MediaType.TEXT_PLAIN_VALUE)
    public String statement(@PathVariable String iban,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since) {
        return Mt940Writer.write(renderer.render(iban, store.find(iban, since)));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```sh
./gradlew :apps:fakebank:test --tests 'pl.najem.fakebank.StatementRendererTest'
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```sh
./gradlew build
git add apps/fakebank/build.gradle.kts \
  apps/fakebank/src/main/java/pl/najem/fakebank/StatementRenderer.java \
  apps/fakebank/src/main/java/pl/najem/fakebank/StatementController.java \
  apps/fakebank/src/test/java/pl/najem/fakebank/StatementRendererTest.java
git commit -m "Export seeded FakeBank transactions as MT940 statements"
```

---

### Task 6: The round trip over all 13 scenarios

This is the task the coordinator's both-sides-together ruling exists for. A parser tested against hand-written sample files is tested against someone's guess about what a bank sends. This one is tested against the fixtures accounting's ladder actually uses.

**Files:**
- Create: `apps/fakebank/src/test/java/pl/najem/fakebank/StatementRoundTripTest.java`
- Modify: `apps/fakebank/README.md`

**Interfaces:**
- Consumes: `ScenarioCatalog.names()`, `ScenarioCatalog.generate`, `StatementRenderer.render`, `Mt940Writer.write`, `Mt940Reader.read`.
- Produces: nothing. This is a test and a document.

**What the round trip does and does not assert.** It compares the *transaction*: amount, both dates, remittance text, counterparty, direction. It does **not** compare the external id, and that is deliberate — `on-time/NAJEM-M1-2026/0` is a property of FakeBank's transport, not of the transaction. A real bank has never heard of it. Asserting id equality would only be possible by smuggling our own id through a field banks use for their own purposes, which would make the fixture less like a bank, not more. The id for an ingested MT940 line is derived on the accounting side (open decision 2).

- [ ] **Step 1: Write the failing test**

`StatementRoundTripTest.java`:

```java
package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import pl.najem.mt940.Mt940Line;
import pl.najem.mt940.Mt940Mark;
import pl.najem.mt940.Mt940Reader;
import pl.najem.mt940.Mt940Statement;
import pl.najem.mt940.Mt940Writer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every scenario survives the trip out through MT940 and back.
 *
 * <p>External ids are not compared: an id is a property of FakeBank's transport, not of the
 * transaction, and a real bank has never heard of ours.
 */
class StatementRoundTripTest {

    private static final String IBAN = "PL61109010140000071219812874";
    private static final String REFERENCE = "NAJEM/M1/2026";
    private static final BigDecimal AMOUNT = new BigDecimal("2500.00");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 9, 1);

    private final ScenarioCatalog catalog = new ScenarioCatalog();
    private final StatementRenderer renderer = new StatementRenderer();

    static Set<String> scenarios() {
        return new ScenarioCatalog().names();
    }

    private List<Mt940Line> exportAndReread(List<BankTransactionDto> seeded) {
        String text = Mt940Writer.write(renderer.render(IBAN, seeded));
        List<Mt940Line> lines = new ArrayList<>();
        for (Mt940Statement statement : Mt940Reader.read(text)) {
            lines.addAll(statement.lines());
        }
        return lines;
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void everyScenarioSurvivesTheRoundTrip(String scenario) {
        List<BankTransactionDto> seeded =
            catalog.generate(new ScenarioRequest(scenario, IBAN, REFERENCE, AMOUNT, ANCHOR));

        List<Mt940Line> reread = exportAndReread(seeded);

        assertThat(reread).hasSameSizeAs(seeded);
        for (int i = 0; i < seeded.size(); i++) {
            BankTransactionDto expected = seeded.get(i);
            Mt940Line actual = reread.get(i);
            assertThat(actual.amount()).as("amount of %s line %d", scenario, i)
                .isEqualByComparingTo(expected.amount());
            assertThat(actual.bookingDate()).as("booking date of %s line %d", scenario, i)
                .isEqualTo(expected.bookingDate());
            assertThat(actual.valueDate()).as("value date of %s line %d", scenario, i)
                .isEqualTo(expected.valueDate());
            assertThat(actual.remittanceInfo()).as("title of %s line %d", scenario, i)
                .isEqualTo(expected.title());
            assertThat(actual.counterpartyName()).as("counterparty of %s line %d", scenario, i)
                .isEqualTo(expected.counterpartyName());
            assertThat(actual.counterpartyIban()).as("counterparty IBAN of %s line %d", scenario, i)
                .isEqualTo(expected.counterpartyIban());
            assertThat(actual.bankReference()).as("bank reference of %s line %d", scenario, i)
                .isEqualTo(expected.bankReference());
            assertThat(actual.mark().name()).as("direction of %s line %d", scenario, i)
                .isEqualTo("DBIT".equals(expected.creditDebitIndicator()) ? "D" : "C");
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void noScenarioEverProducesASignedAmount(String scenario) {
        List<Mt940Line> reread = exportAndReread(
            catalog.generate(new ScenarioRequest(scenario, IBAN, REFERENCE, AMOUNT, ANCHOR)));

        assertThat(reread).allSatisfy(line -> assertThat(line.amount()).isPositive());
    }

    @Test
    void theOutgoingDebitStaysADebit() {
        List<Mt940Line> reread = exportAndReread(
            catalog.generate(new ScenarioRequest("outgoing-debit", IBAN, REFERENCE, AMOUNT, ANCHOR)));

        assertThat(reread).singleElement()
            .satisfies(line -> assertThat(line.mark()).isEqualTo(Mt940Mark.D));
    }

    @Test
    void exportingTwiceProducesByteIdenticalText() {
        List<BankTransactionDto> seeded =
            catalog.generate(new ScenarioRequest("partial-then-topup", IBAN, REFERENCE, AMOUNT, ANCHOR));

        assertThat(Mt940Writer.write(renderer.render(IBAN, seeded)))
            .isEqualTo(Mt940Writer.write(renderer.render(IBAN, seeded)));
    }
}
```

Note `no-reference` and `third-party-payer` seed an empty title; the reader returns `""` for an absent or empty remittance, so the assertion holds without a special case. If it does not, **fix the reader, not the test** — an empty remittance is exactly what a reference-less transfer looks like, and accounting's tiers 3–4 depend on it arriving as empty rather than as null.

- [ ] **Step 2: Run test to verify it fails**

```sh
./gradlew :apps:fakebank:test --tests 'pl.najem.fakebank.StatementRoundTripTest'
```

Expected: FAIL — compile error, `StatementRoundTripTest` references nothing new, so if Task 5 is complete this may pass immediately. **If it passes on the first run, that is not a red and the task is not done honestly.** Break it deliberately once — change `~20` to `~21` in `StatementRenderer` is not possible since the writer owns it, so instead temporarily make `Mt940Writer.information` omit `~38`, confirm the counterparty-IBAN assertion fails, then revert. Record what you saw.

- [ ] **Step 3: Fix whatever the round trip caught**

Expect real failures here. The likely ones, and what each means:
- **Remittance longer than the line** — MT940 subfields are conventionally ≤ 35 characters. If a title exceeds it, split across `~20`/`~21`/`~22` in the writer; the reader already joins them in order.
- **A title containing `~`** — would corrupt subfield parsing. No current scenario does, but assert it: reject or escape in the writer rather than emit ambiguous text.
- **Value date absent** — the renderer already falls back to the booking date.

- [ ] **Step 4: Run test to verify it passes**

```sh
./gradlew :apps:fakebank:test
```

Expected: PASS. 24 Phase 1 tests + 6 from Task 5 + 29 here (13 + 13 + 2) = **59 fakebank tests**. Read the count from `apps/fakebank/build/test-results/test/*.xml`, not the console.

- [ ] **Step 5: Document the export, then commit**

In `apps/fakebank/README.md`, replace the `## Not here` section with:

````markdown
## Exporting a statement

The same seeded lines, in the format a bank would hand over:

```
GET /api/accounts/{iban}/statement.mt940?since=YYYY-MM-DD   →   text/plain
```

One statement per currency, alphabetically, numbered `1/1`, `2/1`, … — MT940 states the currency
once on the balance fields, so a `foreign-currency` seed cannot share a statement with a PLN one.
Exporting the same seed twice yields byte-identical text.

The title travels in `:86:~20`, the counterparty in `~32`/`~38`, and the bank's own reference after
the `//` in `:61:`. Direction is the `C`/`D` mark; amounts stay positive, as everywhere else here.

**External ids do not survive the trip, by design.** `on-time/NAJEM-M1-2026/0` is a property of this
service's JSON transport, not of the transaction — a real bank has never heard of it. An ingested
MT940 line gets its id from the importing side. `StatementRoundTripTest` therefore compares
amounts, dates, remittance, counterparty and direction, and deliberately not ids.
````

```sh
./gradlew build
git add apps/fakebank/src/test/java/pl/najem/fakebank/StatementRoundTripTest.java apps/fakebank/README.md
git commit -m "Round-trip every scenario through MT940 and document the export"
```

---

### Task 7: The accounting-side upload — GATED

**Do not start this task until the three open decisions above are answered on the topic.** Post the answers into this plan first.

**Files (assuming decision 3 resolves to "this agent writes it, with per-file consent"):**
- Create: `modules/accounting/src/main/java/pl/najem/acc/adapter/statement/Mt940Import.java`
- Create: `modules/accounting/src/main/java/pl/najem/acc/adapter/statement/StatementUploadController.java`
- Test: `modules/accounting/src/test/java/pl/najem/acc/adapter/statement/Mt940ImportTest.java`

**Interfaces:**
- Consumes: `Mt940Reader.read`, `BankLine`, `IngestionService.ingest(BankLine)`.
- Produces: `Mt940Import.toBankLines(String text) -> List<BankLine>`; `POST /api/acc/statements` accepting `text/plain`.

**Shape, subject to accounting's answers:**

```java
/** An uploaded statement is push, not pull: it goes straight to ingestion, not through
 *  BankStatementPort, which asks the bank what happened since a date an upload cannot honour. */
public List<BankLine> toBankLines(String text) {
    List<BankLine> lines = new ArrayList<>();
    for (Mt940Statement statement : Mt940Reader.read(text)) {
        int index = 0;
        for (Mt940Line line : statement.lines()) {
            lines.add(new BankLine(
                externalId(statement, index++),      // open decision 2
                line.amount(),                       // positive; direction is in the mark
                line.remittanceInfo(),
                line.bookingDate()));
        }
    }
    return lines;
}

private static String externalId(Mt940Statement statement, int index) {
    return "mt940/" + statement.account() + "/" + statement.statementNumber() + "/" + index;
}
```

**Tests this task must have, whoever writes it:**
1. Uploading the MT940 export of a seeded scenario ingests the same payments the polling path would.
2. **Uploading the same file twice ingests nothing the second time.** This is the dedup guarantee and it is the one that matters — re-uploading is the ordinary operator action, not the exotic one.
3. A `DBIT` line does not become a rent payment. (The `outgoing-debit` scenario exists for this.)
4. Malformed text is rejected with a 400 and the offending text in the message, ingesting nothing — a partial import is worse than a refused one.

**Open question for accounting to answer in this task, not before:** whether a debit line should be ingested at all, or filtered at the boundary. Filtering is tempting and probably wrong — a reconciliation screen that cannot see outgoing money cannot reconcile — but it is accounting's call, and `BankLine` cannot currently express the difference (open decision 1).

---

## Self-Review

**Spec coverage.** The coordinator's seq 85 ruling named three requirements: `platform/mt940` as a dependency-free leaf (Task 1, with the build file carrying a comment saying so), the round-trip assertion written as a test rather than prose (Task 6), and positive amounts with direction in the indicator (asserted in Tasks 1, 4, 5 and 6 — four independent places, because it is the convention most likely to be silently broken by a well-meaning refactor). The accounting-side half is Task 7 and is explicitly gated rather than guessed at.

**Placeholder scan.** Every code step carries real code. Task 7 is the exception and is marked GATED with its dependencies named, rather than filled with invented answers to questions accounting owns.

**Type consistency.** `Mt940Line`'s nine components appear in the same order in Task 1's definition, Task 3's `withInformation`, Task 4's writer and Task 5's renderer. `Mt940Reader.read` and `Mt940Writer.write` are static throughout. `StatementRenderer.render(String, List<BankTransactionDto>)` is called with the same signature in Tasks 5 and 6.

**One thing I could not verify and am flagging rather than hiding.** Task 6's step 2 will probably not go red on its own, because Task 5 already provides everything it compiles against. I have written the step to require deliberately breaking the writer to confirm the assertions have teeth, and to record what was seen. A round-trip test that has never failed is a test whose passing means nothing.
