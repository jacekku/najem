# Add-property and add-unit screens — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put two write screens over `PortfolioService.createProperty` and `PortfolioService.addUnit`, so a building and its flats can be entered from a browser instead of `curl`.

**Architecture:** Thymeleaf screens in `apps/najem-app` calling existing PM application services. The property form is two-phase (address + owners, then shares) so a picker round-trip cannot discard typed input — the same shape as the existing `ReserveScreenController`. No new port, no new adapter, no new table, no migration. The only changes below the web layer are creation guards in the PM domain and a return type that carries warnings out of `createProperty`.

**Tech Stack:** Java 21 (Temurin), Spring Boot 3.3.5, Gradle, Thymeleaf, Postgres + Flyway, Testcontainers, JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-08-07-add-property-and-unit-design.md`

## Global Constraints

These bind every task. A reviewer checks them regardless of what the task text says.

- **Build first:** every implementer runs `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"` before any gradle command. Bash tool, not PowerShell.
- **Fast tier is the inner loop:** `./gradlew build` (~45s) excludes container tests. `./gradlew build -PintegrationTests` (~8min) includes them. Run gradle in the FOREGROUND with `timeout: 600000` — do not background it.
- **A3:** `pl.najem.pm.domain` imports nothing outside the JDK. No Spring, no clock, no repository.
- **A architecture rule:** `pl.najem.pm.application` must never import `pl.najem.pm.adapter`. Same for `pl.najem.app.web` — it may import module *application* packages only.
- **Rule 10:** policy lives in the layer that owns the decision. "What is a valid property" is domain. "Which Polish sentence to show" is web.
- **Rule 13/14:** a fake that cannot fail is not a test. A test that asserts nothing is a defect, not a placeholder.
- **Rule 12:** when a type maps to stored or wire data, pin the wire values in a test.
- **Rule 21:** after writing a test that is supposed to catch something, mutate the production code to prove it goes red, then revert the mutation.
- **All user-facing copy is Polish.** No English strings in a template or in a message a manager can see. Domain exception messages stay English (they are for logs and the API) and the web layer supplies its own Polish copy — that is the established split, see `ReserveScreenController.overlapMessage`.
- **Amounts:** `@RequestParam BigDecimal` with `<input type="number" step="0.01">`. Do not write a Polish-comma parser. Rendering uses `${#numbers.formatDecimal(x, 1, 'WHITESPACE', 2, 'COMMA')}` — format is STATED, never inherited from the server locale.
- **Every `th:fragment` lives in its own file.** A fragment declared inline in a page renders twice — once where it sits and once where it is inserted.
- **CSS variables that exist:** use `var(--rule)` for borders. There is no `--line`.
- **Commit style:** no LLM signature, no mention of an LLM in the message. Subject line is a sentence about what changed, not a conventional-commits prefix.

## File Structure

```
NEW  apps/najem-app/src/main/java/pl/najem/app/web/AddPropertyScreenController.java
NEW  apps/najem-app/src/main/java/pl/najem/app/web/AddUnitScreenController.java
NEW  apps/najem-app/src/main/java/pl/najem/app/web/OwnerDraft.java
NEW  apps/najem-app/src/main/resources/templates/property-new.html
NEW  apps/najem-app/src/main/resources/templates/owners-form.html
NEW  apps/najem-app/src/main/resources/templates/unit-new.html
MOD  apps/najem-app/src/main/resources/templates/properties.html
MOD  apps/najem-app/src/main/resources/templates/units.html
MOD  apps/najem-app/src/main/resources/static/css/najem.css
MOD  modules/propertymanagement/src/main/java/pl/najem/pm/domain/Property.java
MOD  modules/propertymanagement/src/main/java/pl/najem/pm/domain/Unit.java
MOD  modules/propertymanagement/src/main/java/pl/najem/pm/application/PortfolioService.java
MOD  modules/propertymanagement/src/main/java/pl/najem/pm/adapter/rest/PortfolioController.java

NEW  modules/propertymanagement/src/test/java/pl/najem/pm/domain/PortfolioRulesTest.java
NEW  apps/najem-app/src/test/java/pl/najem/app/web/OwnerDraftTest.java
NEW  apps/najem-app/src/test/java/pl/najem/app/web/AddUnitScreenTest.java
NEW  apps/najem-app/src/test/java/pl/najem/app/web/AddPropertyScreenTest.java
```

---

### Task 1: Creation guards in the PM domain

Neither `Property.create` nor `Unit.add` validates anything today. A blank address or a null base rent produces a corrupt aggregate that every downstream screen then has to defend against. The guard belongs here, not in a controller, because "what is a valid property" is the domain's question (rule 10) and a guard here is testable in milliseconds without a container.

**Files:**
- Modify: `modules/propertymanagement/src/main/java/pl/najem/pm/domain/Property.java` (the `create` factory, around line 25)
- Modify: `modules/propertymanagement/src/main/java/pl/najem/pm/domain/Unit.java` (the `add` factory, around line 32)
- Create: `modules/propertymanagement/src/test/java/pl/najem/pm/domain/PortfolioRulesTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Property.create` and `Unit.add` now throw `IllegalArgumentException` on invalid input. `Property.warnings()` is unchanged and still returns `Warnings`. Task 2 relies on both.

- [ ] **Step 1: Write the failing tests**

Create `modules/propertymanagement/src/test/java/pl/najem/pm/domain/PortfolioRulesTest.java`:

```java
package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a property and a unit must have to exist at all.
 *
 * <p>Fast tier: no container, no Spring. These are the guards that make every screen downstream
 * able to render a property without checking for nulls first, so they are worth a millisecond.
 */
class PortfolioRulesTest {

    private static final UUID PROPERTY = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();

    @Test
    void apropertyWithoutAnAddressIsNotAProperty() {
        assertThatThrownBy(() -> Property.create(PROPERTY, WORKSPACE, null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("address");
    }

    /**
     * Whitespace, not only null. A form posts "   " when somebody tabs through the field, and a
     * property addressed with three spaces renders as a blank clickable row on the portfolio board.
     */
    @Test
    void anaddressOfNothingButSpacesIsNotAnAddress() {
        assertThatThrownBy(() -> Property.create(PROPERTY, WORKSPACE, "   ", List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("address");
    }

    @Test
    void apropertyWithAnAddressIsCreated() {
        var events = Property.create(PROPERTY, WORKSPACE, "Krucza 12/4, Warszawa", List.of());

        assertThat(events).hasSize(1);
        assertThat(Property.from(events).address()).isEqualTo("Krucza 12/4, Warszawa");
    }

    @Test
    void aunitWithoutANameIsNotAUnit() {
        assertThatThrownBy(() -> Unit.add(UNIT, WORKSPACE, PROPERTY, "  ", new BigDecimal("2500")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void aunitWithoutABaseRentIsNotAUnit() {
        assertThatThrownBy(() -> Unit.add(UNIT, WORKSPACE, PROPERTY, "M2", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base rent");
    }

    @Test
    void anegativeBaseRentIsRefused() {
        assertThatThrownBy(() -> Unit.add(UNIT, WORKSPACE, PROPERTY, "M2", new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base rent");
    }

    /**
     * Zero is allowed and this is not an oversight. A caretaker's flat let rent-free as part of a
     * job is a real arrangement, and the domain has no business having an opinion about it. The
     * guard refuses absent and negative, which are mistakes; it does not refuse cheap.
     */
    @Test
    void arentFreeUnitIsAllowed() {
        var events = Unit.add(UNIT, WORKSPACE, PROPERTY, "Portiernia", BigDecimal.ZERO);

        assertThat(Unit.from(events).baseRent()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void ownersTotallingOneHundredRaiseNoWarning() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("50")),
            new Owner(UUID.randomUUID(), new BigDecimal("50")))));

        assertThat(property.warnings().messages()).isEmpty();
    }

    /**
     * The message must name the actual total. "Shares are wrong" tells a manager to go and add up
     * three numbers the system already added up.
     */
    @Test
    void ownersTotallingNinetyRaiseAWarningNamingNinety() {
        var property = Property.from(Property.create(PROPERTY, WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("60")),
            new Owner(UUID.randomUUID(), new BigDecimal("30")))));

        assertThat(property.warnings().messages()).singleElement().asString().contains("90");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :modules:propertymanagement:test --tests '*PortfolioRulesTest*'
```

Expected: the four guard tests FAIL (no exception is thrown, so `assertThatThrownBy` reports "Expecting code to raise a throwable"). The three positive tests should already PASS.

- [ ] **Step 3: Add the guard to `Property.create`**

In `Property.java`, replace the body of `create`:

```java
    public static List<Object> create(UUID propertyId, UUID workspaceId, String address,
                                      List<Owner> owners) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("A property needs an address");
        }
        return List.of(new PropertyEvents.PropertyCreated(workspaceId, propertyId, address.strip(),
            owners == null ? List.of() : List.copyOf(owners)));
    }
```

Note two things beyond the guard, both deliberate: the address is stripped so a trailing space cannot make two identical buildings look different, and a null owner list becomes empty rather than being carried into an event that something will later iterate.

- [ ] **Step 4: Add the guard to `Unit.add`**

In `Unit.java`, replace the body of `add`:

```java
    public static List<Object> add(UUID unitId, UUID workspaceId, UUID propertyId,
                                   String name, BigDecimal baseRent) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A unit needs a name");
        }
        if (baseRent == null || baseRent.signum() < 0) {
            throw new IllegalArgumentException("A unit needs a base rent of zero or more");
        }
        return List.of(new UnitEvents.UnitAddedToProperty(workspaceId, unitId, propertyId,
            name.strip(), baseRent));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :modules:propertymanagement:test --tests '*PortfolioRulesTest*'
```

Expected: PASS, 9 tests.

- [ ] **Step 6: Run the whole fast tier — these guards can break existing tests**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build
```

Expected: PASS. If an existing test creates a unit with a null base rent or a property with no address, that test was relying on the absence of the guard — fix the test's fixture data (give it a real address or rent), do NOT weaken the guard. Report in your report file every test you had to touch and what value you gave it.

- [ ] **Step 7: Commit**

```bash
git add modules/propertymanagement/src/main/java/pl/najem/pm/domain/Property.java \
        modules/propertymanagement/src/main/java/pl/najem/pm/domain/Unit.java \
        modules/propertymanagement/src/test/java/pl/najem/pm/domain/PortfolioRulesTest.java
git commit -m "Refuse a property with no address and a unit with no rent"
```

---

### Task 2: `createProperty` returns its warnings

`Property.warnings()` computes the ≠100% share warning today and nothing can reach it: `createProperty` returns a bare `UUID`. The screen in Task 6 needs that list. The shape copies `TenancyService.Reservation(tenancyId, warnings)` exactly — same problem, same answer, so a reader who knows one knows the other.

**Files:**
- Modify: `modules/propertymanagement/src/main/java/pl/najem/pm/application/PortfolioService.java` (the `createProperty` method, lines 50-56)
- Modify: `modules/propertymanagement/src/main/java/pl/najem/pm/adapter/rest/PortfolioController.java` (the `createProperty` handler, lines 41-49)
- Create: `modules/propertymanagement/src/test/java/pl/najem/pm/application/PortfolioServiceTest.java`

**Interfaces:**
- Consumes: `Property.create(...)` and `Property.warnings()` from Task 1.
- Produces:
  ```java
  public record CreatedProperty(UUID propertyId, List<String> warnings) {}
  public CreatedProperty PortfolioService.createProperty(UUID workspaceId, String address, List<Owner> owners)
  ```
  Task 6's controller calls this and reads `.propertyId()` and `.warnings()`.

- [ ] **Step 1: Write the failing test**

Create `modules/propertymanagement/src/test/java/pl/najem/pm/application/PortfolioServiceTest.java`.

Look first at an existing test in this package to see how a `PortfolioService` is assembled with an in-memory `EventStore` and an in-memory `PortfolioProjection` — `TenancyRulesTest` in the same directory shows the pattern for `TenancyService`, and `InMemoryEventStore` lives in `platform:eventstore`'s test fixtures. Use whatever in-memory `PortfolioProjection` double already exists in `modules/propertymanagement/src/test`; if there is none, write one in the same package, storing what it is told in a `Map` and exposing it for assertions. Do not mock the projection with a mocking framework — this codebase uses hand-written in-memory doubles (A5).

```java
    /**
     * The id the caller gets back must be the id the event was appended under. They are produced
     * one line apart from the same variable today, which is exactly the kind of pairing that
     * survives a refactor by luck. Asserting it costs nothing and pins it.
     */
    @Test
    void thereturnedIdIsTheStreamTheEventWasAppendedUnder() {
        var created = portfolio.createProperty(WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        var property = Property.from(store.load(created.propertyId(), "Property").events());
        assertThat(property.address()).isEqualTo("Krucza 12");
        assertThat(property.workspaceId()).isEqualTo(WORKSPACE);
    }

    @Test
    void sharesTotallingOneHundredComeBackWithNoWarnings() {
        var created = portfolio.createProperty(WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("50")),
            new Owner(UUID.randomUUID(), new BigDecimal("50"))));

        assertThat(created.warnings()).isEmpty();
    }

    /**
     * The property is created ANYWAY. This is the whole decision the spec records: a half-known
     * share register is a real thing a manager has to be able to write down, and Property's own
     * comment says "soft check — confirm, don't block". A test that only asserted the warning
     * would pass equally well against an implementation that threw.
     */
    @Test
    void sharesTotallingNinetyStillCreateThePropertyAndComeBackWarned() {
        var created = portfolio.createProperty(WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("60")),
            new Owner(UUID.randomUUID(), new BigDecimal("30"))));

        assertThat(Property.from(store.load(created.propertyId(), "Property").events()).address())
            .isEqualTo("Krucza 12");
        assertThat(created.warnings()).singleElement().asString().contains("90");
    }

    @Test
    void apropertyWithOneOwnerHoldingEverythingIsClean() {
        var created = portfolio.createProperty(WORKSPACE, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100"))));

        assertThat(created.warnings()).isEmpty();
    }
```

Write the class shell (package, imports, fields, `@BeforeEach` wiring) around these four methods.

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :modules:propertymanagement:test --tests '*PortfolioServiceTest*'
```

Expected: COMPILE FAILURE — `createProperty` returns `UUID`, which has no `propertyId()`.

- [ ] **Step 3: Change the service**

In `PortfolioService.java`, replace `createProperty` and add the record:

```java
    /**
     * The one command with nothing to check against: a property that did not exist a moment ago has
     * no prior owner, so the caller's workspace is stamped on it rather than compared to it.
     *
     * <p>Returns the warnings rather than only the id. Ownership shares that do not total 100% are
     * a soft check by decision — {@link Property}'s own comment says confirm, do not block, because
     * a half-known share register is a thing a manager legitimately has to record. A soft check
     * nobody can see is the same as no check, so the warning has to leave the service.
     */
    public CreatedProperty createProperty(UUID workspaceId, String address, List<Owner> owners) {
        UUID propertyId = UUID.randomUUID();
        var events = Property.create(propertyId, workspaceId, address, owners);
        store.append(propertyId, "Property", 0, events, List.of());
        projection.propertyCreated(propertyId, workspaceId, address);
        return new CreatedProperty(propertyId, Property.from(events).warnings().messages());
    }

    /** A created property and the soft warnings raised against it. */
    public record CreatedProperty(UUID propertyId, List<String> warnings) {
    }
```

Note: `projection.propertyCreated` is passed the caller's `address`, which is now unstripped while the event carries the stripped form. Pass the stripped value instead — read it back off the rebuilt property so there is one source:

```java
        var property = Property.from(events);
        store.append(propertyId, "Property", 0, events, List.of());
        projection.propertyCreated(propertyId, workspaceId, property.address());
        return new CreatedProperty(propertyId, property.warnings().messages());
```

Use this second form. Building the property once and using it for both the projection and the warnings is what keeps the derived row and the record saying the same thing.

- [ ] **Step 4: Fix the REST caller**

In `PortfolioController.java`:

```java
        return new PropertyCreated(portfolio.createProperty(
            workspaceId, request.address(), owners).propertyId());
```

The REST wire shape `PropertyCreated(UUID propertyId)` does NOT change — this is rule 12 territory, an API response shape is stored data as far as its clients are concerned. Warnings are not added to it in this change; no API client asked for them and inventing a field costs a version.

- [ ] **Step 5: Run to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build
```

Expected: PASS. Other callers of `createProperty` exist in test sources (notably `apps/najem-app/src/test/.../ReserveScreenTest` and PM's own tests) — fix each to call `.propertyId()`. List every file you touched in your report.

- [ ] **Step 6: Mutate to prove the warning test bites (rule 21)**

Temporarily change the service to `return new CreatedProperty(propertyId, List.of());`. Re-run `PortfolioServiceTest`. Expected: `sharesTotallingNinetyStillCreateThePropertyAndComeBackWarned` goes RED. Revert the mutation and confirm green. Record both outcomes in your report file.

- [ ] **Step 7: Commit**

```bash
git add modules/propertymanagement/src/main/java/pl/najem/pm/application/PortfolioService.java \
        modules/propertymanagement/src/main/java/pl/najem/pm/adapter/rest/PortfolioController.java \
        modules/propertymanagement/src/test/java/pl/najem/pm/application/PortfolioServiceTest.java
git commit -m "Let a share register that does not add up say so"
```

(Also `git add` any test files you had to fix for the new return type.)

---

### Task 3: `OwnerDraft`

The owner list is carried in the query string, like `PartiesDraft`, so the add-property screen stays bookmarkable and needs no session. This task is the parsing rule on its own, with no screen yet.

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/OwnerDraft.java`
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/OwnerDraftTest.java`

**Interfaces:**
- Consumes: `UnitScreenController.chosen(String)` returning `Optional<UUID>` (already exists — returns empty for null/blank/malformed), `InvalidContactIdException(String value, Throwable cause)`, `DuplicatePartyException(UUID id)`. All three are in `pl.najem.app.web`.
- Produces: `OwnerDraft.of(List<String> raw)` returning `OwnerDraft`, with `List<UUID> owners()`. Tasks 5 and 6 use it.

- [ ] **Step 1: Write the failing test**

Create `apps/najem-app/src/test/java/pl/najem/app/web/OwnerDraftTest.java`:

```java
package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The owner list as it survives a round trip through the query string.
 *
 * <p>Fast tier, no Spring. Mirrors {@link PartiesDraft} and refuses the same two things for the
 * same reasons — a malformed id must not silently vanish, because a property created with fewer
 * owners than the manager saw on screen is a wrong record that looks like a successful write.
 */
class OwnerDraftTest {

    private static final String ANNA = "11111111-1111-4111-8111-111111111111";
    private static final String PIOTR = "22222222-2222-4222-8222-222222222222";

    @Test
    void nooneDraftedIsAnEmptyList() {
        assertThat(OwnerDraft.of(null).owners()).isEmpty();
        assertThat(OwnerDraft.of(List.of()).owners()).isEmpty();
    }

    /** Order is the order they were added — it is what the share inputs line up against. */
    @Test
    void draftedOwnersKeepTheOrderTheyWereAddedIn() {
        assertThat(OwnerDraft.of(List.of(ANNA, PIOTR)).owners())
            .containsExactly(UUID.fromString(ANNA), UUID.fromString(PIOTR));
    }

    /**
     * One person owns one combined share, not two entries a manager then has to notice sum wrongly.
     */
    @Test
    void thesamePersonCannotOwnTwoShares() {
        assertThatThrownBy(() -> OwnerDraft.of(List.of(ANNA, PIOTR, ANNA)))
            .isInstanceOf(DuplicatePartyException.class);
    }

    @Test
    void agarbledIdIsRefusedRatherThanSkipped() {
        assertThatThrownBy(() -> OwnerDraft.of(List.of(ANNA, "not-a-uuid")))
            .isInstanceOf(InvalidContactIdException.class);
    }

    @Test
    void ablankIdIsAsMuchAMistakeAsAGarbledOne() {
        assertThatThrownBy(() -> OwnerDraft.of(List.of("   ")))
            .isInstanceOf(InvalidContactIdException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :apps:najem-app:test --tests '*OwnerDraftTest*'
```

Expected: COMPILE FAILURE — `OwnerDraft` does not exist.

- [ ] **Step 3: Write `OwnerDraft`**

```java
package pl.najem.app.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who owns the property being created, carried in the query string so the screen stays bookmarkable
 * and needs no session.
 *
 * <p>Order is preserved because the share inputs on phase two line up against it positionally: the
 * n-th input is the n-th owner's share, and a reordering would silently reassign shares between
 * people.
 *
 * <p>A malformed id is refused rather than skipped, on the same grounds as {@link PartiesDraft}: a
 * garbled value silently dropping an owner would create a property owned by fewer people than the
 * manager saw on the screen they submitted, and nothing would say so.
 */
public record OwnerDraft(List<UUID> owners) {

    public static OwnerDraft of(List<String> raw) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<UUID> parsed = new ArrayList<>();
        if (raw != null) {
            for (String value : raw) {
                UUID id = UnitScreenController.chosen(value)
                    .orElseThrow(() -> new InvalidContactIdException(value, null));
                if (!seen.add(id)) {
                    throw new DuplicatePartyException(id);
                }
                parsed.add(id);
            }
        }
        return new OwnerDraft(List.copyOf(parsed));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :apps:najem-app:test --tests '*OwnerDraftTest*'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/najem-app/src/main/java/pl/najem/app/web/OwnerDraft.java \
        apps/najem-app/src/test/java/pl/najem/app/web/OwnerDraftTest.java
git commit -m "Carry a drafted owner list through the query string"
```

---

### Task 4: The add-unit screen

The simpler of the two screens, done first so the harder one has a working pattern to copy.

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/AddUnitScreenController.java`
- Create: `apps/najem-app/src/main/resources/templates/unit-new.html`
- Modify: `apps/najem-app/src/main/resources/templates/units.html` (add the button)
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/AddUnitScreenTest.java`

**Interfaces:**
- Consumes: `PortfolioService.addUnit(UUID workspaceId, UUID propertyId, String name, BigDecimal baseRent)` returning `UUID`; `PortfolioService.requireOwnsProperty(UUID workspaceId, UUID propertyId)` throwing `UnknownInThisWorkspaceException`; `WebWorkspace workspace` resolved as a handler argument, with `workspace.workspaceId()`.
- Produces: routes `GET /properties/{propertyId}/units/new` and `POST /properties/{propertyId}/units`.

- [ ] **Step 1: Write the controller**

```java
package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pl.najem.pm.application.PortfolioService;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Adding a flat to a building.
 *
 * <p>The GET checks ownership before it renders. {@code addUnit} checks it again on the way in, so
 * this is not the security boundary — it is the difference between a form that will work and a form
 * that looks fine and fails on submit. Rendering a write form for a building the caller cannot
 * write to is a screen that lies about what is going to happen.
 *
 * <p>Two submit buttons, distinguished by {@code action}: a twenty-flat building is entered in one
 * sitting, and going back to the board between each one is twenty round trips to look at a list
 * nobody is reading yet.
 */
@Controller
public class AddUnitScreenController {

    private final PortfolioService portfolio;

    public AddUnitScreenController(PortfolioService portfolio) {
        this.portfolio = portfolio;
    }

    @GetMapping("/properties/{propertyId}/units/new")
    public String form(@PathVariable UUID propertyId,
                       @RequestParam(name = "added", required = false) String added,
                       WebWorkspace workspace, Model model) {
        portfolio.requireOwnsProperty(workspace.workspaceId(), propertyId);
        model.addAttribute("propertyId", propertyId);
        model.addAttribute("added", added);
        return "unit-new";
    }

    @PostMapping("/properties/{propertyId}/units")
    public String create(@PathVariable UUID propertyId,
                         @RequestParam String name,
                         @RequestParam BigDecimal baseRent,
                         @RequestParam(defaultValue = "save") String action,
                         WebWorkspace workspace, RedirectAttributes flash) {
        UUID workspaceId = workspace.workspaceId();
        portfolio.addUnit(workspaceId, propertyId, name, baseRent);

        if ("another".equals(action)) {
            // The name rides in the query string rather than a flash attribute because the manager
            // may reload this form, and a flash survives exactly one render — the confirmation
            // would vanish on refresh and read as though the unit had not been saved.
            return "redirect:/properties/" + propertyId + "/units/new?added="
                + java.net.URLEncoder.encode(name.strip(), java.nio.charset.StandardCharsets.UTF_8);
        }
        flash.addFlashAttribute("added", name.strip());
        return "redirect:/properties/" + propertyId + "/units";
    }
}
```

- [ ] **Step 2: Write the template**

Create `apps/najem-app/src/main/resources/templates/unit-new.html`. Open `apps/najem-app/src/main/resources/templates/reserve-terms.html` first and copy its outer structure verbatim — the `th:replace="~{layout :: page(...)}"` wrapper, the CSRF handling on the form (check how the existing POST forms do it; if Thymeleaf's Spring dialect injects the token automatically for `th:action` forms, do the same), and the class names it uses.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="pl"
      th:replace="~{layout :: page('Nowy lokal — NAJEM', ~{::main})}">
<body>
<main>
    <h1>Nowy lokal</h1>

    <p class="note" th:if="${added != null}"
       th:text="'Dodano lokal ' + ${added} + '. Możesz dodać kolejny.'">—</p>

    <form th:action="@{/properties/{p}/units(p=${propertyId})}" method="post">
        <label>
            Nazwa lokalu
            <input type="text" name="name" required autofocus placeholder="np. M2">
        </label>

        <label>
            Czynsz bazowy (zł)
            <input type="number" name="baseRent" step="0.01" min="0" required placeholder="2500.00">
        </label>

        <div class="actions">
            <button type="submit" name="action" value="save">Zapisz</button>
            <button type="submit" name="action" value="another" class="secondary">
                Zapisz i dodaj kolejny
            </button>
        </div>
    </form>

    <p><a th:href="@{/properties/{p}/units(p=${propertyId})}">Wróć do listy lokali</a></p>
</main>
</body>
</html>
```

- [ ] **Step 3: Add the button to `units.html`**

Immediately after the `<h1>Lokale</h1>` line, add:

```html
    <p class="actions">
        <a class="button" th:href="@{/properties/{p}/units/new(p=${propertyId})}">Dodaj lokal</a>
    </p>
```

`propertyId` is already on the model — `UnitsScreenController.unitsOf` puts it there.

Check `apps/najem-app/src/main/resources/static/css/najem.css` for whether `.button` and `.actions` already exist. If they do not, add them, using existing custom properties only (`var(--rule)` for borders — there is no `--line`). Keep the addition to what these two screens need.

- [ ] **Step 4: Write the integration test**

Create `apps/najem-app/src/test/java/pl/najem/app/web/AddUnitScreenTest.java`. Copy the `@SpringBootTest` / `@Testcontainers` / `@Tag("integration")` preamble and the `@BeforeEach` workspace-bootstrap block from `apps/najem-app/src/test/java/pl/najem/app/web/ReserveScreenTest.java` verbatim, changing only the `OPERATOR` constant to a different UUID so the two suites cannot collide.

```java
    @Test
    void addingAUnitPutsItOnThePropertysBoard() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "M2").param("baseRent", "2500.00").param("action", "save"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/properties/" + propertyId + "/units"));

        projections.runOnce();
        mvc.perform(get("/properties/" + propertyId + "/units"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("M2")))
            // The format is stated, not inherited: Polish grouping and decimal separator.
            .andExpect(content().string(containsString("2 500,00")));
    }

    @Test
    void savingAndAddingAnotherComesBackToABlankFormNamingWhatWasJustSaved() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        var redirect = mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "M3").param("baseRent", "3000").param("action", "another"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).startsWith("/properties/" + propertyId + "/units/new");

        mvc.perform(get(redirect))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodano lokal M3")))
            // Blank, not prefilled with what was just saved — the next flat has a different name.
            .andExpect(content().string(containsString("name=\"name\"")))
            .andExpect(content().string(not(containsString("value=\"M3\""))));
    }

    @Test
    void aunitWithNoNameIsRefused() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(post("/properties/" + propertyId + "/units").with(csrf())
                .param("name", "   ").param("baseRent", "2500"))
            .andExpect(status().isBadRequest());
    }

    /**
     * Absence and foreign ownership give the same answer, because saying which would confirm that
     * another agency's id is real. Both the form and the submit — a form that renders and then
     * fails on submit is worse than one that never opens.
     */
    @Test
    void anotherAgencysPropertyIsNotFoundOnTheFormOrTheSubmit() throws Exception {
        UUID theirs = portfolio.createProperty(otherWorkspaceId, "Nie nasza 1", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        mvc.perform(get("/properties/" + theirs + "/units/new"))
            .andExpect(status().isNotFound());
        mvc.perform(post("/properties/" + theirs + "/units").with(csrf())
                .param("name", "M2").param("baseRent", "2500"))
            .andExpect(status().isNotFound());
    }

    @Test
    void theunitsBoardOffersTheAddButton() throws Exception {
        UUID propertyId = portfolio.createProperty(workspaceId, "Krucza 12", List.of(
            new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();

        projections.runOnce();
        mvc.perform(get("/properties/" + propertyId + "/units"))
            .andExpect(content().string(containsString("Dodaj lokal")))
            .andExpect(content().string(containsString("/properties/" + propertyId + "/units/new")));
    }
```

You will need a second workspace for `otherWorkspaceId` — `ReserveScreenTest` shows how a workspace is created via `WorkspaceService`. Add `import static org.hamcrest.Matchers.not;` and `containsString` from `org.hamcrest.Matchers`.

- [ ] **Step 5: Run the integration test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :apps:najem-app:test -PintegrationTests --tests '*AddUnitScreenTest*'
```

Expected: PASS, 5 tests. Run in the FOREGROUND with `timeout: 600000`.

- [ ] **Step 6: Mutate to prove the ownership test bites (rule 21)**

Temporarily delete the `portfolio.requireOwnsProperty(...)` line from the GET handler. Re-run. Expected: `anotherAgencysPropertyIsNotFoundOnTheFormOrTheSubmit` goes RED on the GET assertion. Revert and confirm green. Record both in your report.

- [ ] **Step 7: Run the fast tier and commit**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build
git add apps/najem-app/src/main/java/pl/najem/app/web/AddUnitScreenController.java \
        apps/najem-app/src/main/resources/templates/unit-new.html \
        apps/najem-app/src/main/resources/templates/units.html \
        apps/najem-app/src/main/resources/static/css/najem.css \
        apps/najem-app/src/test/java/pl/najem/app/web/AddUnitScreenTest.java
git commit -m "Let a manager add a flat to a building from the board"
```

---

### Task 5: The add-property screen, phase one — address and owners

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/AddPropertyScreenController.java`
- Create: `apps/najem-app/src/main/resources/templates/property-new.html`
- Create: `apps/najem-app/src/main/resources/templates/owners-form.html`
- Modify: `apps/najem-app/src/main/resources/templates/properties.html` (add the button)
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/AddPropertyScreenTest.java`

**Interfaces:**
- Consumes: `OwnerDraft.of(List<String>)` → `OwnerDraft` with `owners()` (Task 3); `ContactDirectory.search(UUID workspaceId, String term)` → `List<ContactMatch>` with `contactId()`, `givenName()`, `surname()`, `email()`; `ContactDirectory.find(UUID, UUID)` → `Optional<ContactDetails>`; `ContactService.registerParty(UUID workspaceId, ContactDetails details, boolean infoClauseServed, LocalDate today)` → `UUID`; `UnitScreenController.chosen(String)` → `Optional<UUID>`; `NoSuchContactException(UUID)`.
- Produces: `GET /properties/new` (phase 1) and `POST /properties/new/owners`. Task 6 adds `?owners=done` and `POST /properties` to the same controller.

- [ ] **Step 1: Write the controller (phase one only)**

```java
package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.NoSuchContactException;
import pl.najem.pm.application.PortfolioService;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Creating a building, in two phases: who owns it, then in what shares.
 *
 * <p>Two phases rather than one screen because picking an owner is a server round trip, and a round
 * trip loses whatever is typed in a <em>sibling</em> form — the browser submits one form, not the
 * page. A single screen holding the address, the share inputs and the picker would silently reset
 * the shares every time the manager searched for a co-owner, and nothing would say so. Phase one
 * carries the address as a hidden field on both picker forms so it survives; phase two asks for
 * shares once, after the owner list is settled, so there is nothing left to lose.
 *
 * <p>This is the shape {@link ReserveScreenController} already uses — draft, then terms — and that
 * is a reason, not a coincidence. A second write screen that behaves differently for no reason
 * costs the manager the thing they learned last week.
 */
@Controller
public class AddPropertyScreenController {

    private final PortfolioService portfolio;
    private final ContactDirectory directory;
    private final ContactService contacts;
    private final Clock clock;

    public AddPropertyScreenController(PortfolioService portfolio, ContactDirectory directory,
                                       ContactService contacts, Clock clock) {
        this.portfolio = portfolio;
        this.directory = directory;
        this.contacts = contacts;
        this.clock = clock;
    }

    @GetMapping("/properties/new")
    public String form(@RequestParam(name = "address", required = false) String address,
                       @RequestParam(name = "owner", required = false) List<String> owners,
                       @RequestParam(name = "q", required = false) String term,
                       @RequestParam(name = "owners", required = false) String phase,
                       WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        var draft = OwnerDraft.of(owners);

        model.addAttribute("address", address == null ? "" : address);
        model.addAttribute("owners", named(workspaceId, draft.owners()));
        // Raw ids alongside the named views: rebuilding an "add one" or "remove one" link from
        // NamedOwner in the template would need a SpringEL projection over contactId(), and this
        // codebase learned the hard way that a SpEL selection rebinds the root to each element.
        model.addAttribute("ownerIds", draft.owners());

        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));
        model.addAttribute("phase", "owners");
        return "property-new";
    }

    /**
     * An owner the agency does not already hold. Registered under {@code contract}: the agency holds
     * this person's data because it manages their building under a contract with them, which is what
     * {@link ContactService#registerParty} stamps.
     */
    @PostMapping("/properties/new/owners")
    public String addOwner(@RequestParam(name = "address", required = false) String address,
                           @RequestParam(name = "owner", required = false) List<String> owners,
                           @RequestParam(required = false) String contactId,
                           @RequestParam(required = false) String givenName,
                           @RequestParam(required = false) String surname,
                           @RequestParam(required = false) String email,
                           @RequestParam(required = false) String phone,
                           @RequestParam(defaultValue = "false") boolean infoClauseServed,
                           WebWorkspace workspace) {
        UUID workspaceId = workspace.workspaceId();
        UUID added = UnitScreenController.chosen(contactId)
            .orElseGet(() -> contacts.registerParty(workspaceId,
                new ContactDetails(givenName, surname, email, phone),
                infoClauseServed, LocalDate.now(clock)));

        List<String> next = ids(OwnerDraft.of(owners).owners());
        next.add(added.toString());
        // Re-validated rather than appended blindly: the duplicate rule has to hold for the person
        // just added, and OwnerDraft is the only place that rule lives. The result builds the
        // redirect below, so this is not a call kept only for its exception.
        var validated = OwnerDraft.of(next);

        return "redirect:" + UriComponentsBuilder.fromPath("/properties/new")
            .queryParam("address", address == null ? "" : address)
            .queryParam("owner", ids(validated.owners()))
            .build().toUriString();
    }

    private List<NamedOwner> named(UUID workspaceId, List<UUID> ids) {
        return ids.stream()
            .map(id -> new NamedOwner(id, directory.find(workspaceId, id)
                    .orElseThrow(() -> new NoSuchContactException(id)),
                ids.stream().filter(other -> !other.equals(id)).toList()))
            .toList();
    }

    private static List<String> ids(List<UUID> owners) {
        return owners.stream().map(UUID::toString).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * A drafted owner with a name to show. {@code others} is the list with this owner taken out —
     * what the Usuń link carries. Computed here, not in the template: a SpringEL selection
     * {@code ${ownerIds.?[#this != owner.contactId()]}} rebinds the root object to each element, so
     * {@code owner} resolves against a {@code UUID} and every render with any drafted owner throws.
     */
    public record NamedOwner(UUID contactId, ContactDetails details, List<UUID> others) {
    }
}
```

- [ ] **Step 2: Write `owners-form.html`**

Its own file. A `th:fragment` declared inline in a page is still part of that page's document, so Thymeleaf renders it where it sits AND again where it is inserted — that shipped a duplicate unlabelled picker last time and no test caught it, because every assertion asked whether the form was present and none asked how many there were.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="pl">
<body>
<!--/*
  Search-then-create for a property's owners. Copied in shape from parties-form.html, and separate
  from it on purpose: the hidden fields are entirely different (address + owner, versus interestId +
  tenant + guarantor + role), so one shared fragment would take five parameters of which each caller
  passes null for three.

  "Type something" and "nobody matched" stay two messages, because a blank term and a term that
  matched nothing both render zero hits — the difference cannot come from the result.

  The typed address rides along as a hidden field on BOTH forms. That is what makes it survive a
  search or an inline creation without a session.
*/-->
<div th:fragment="ownersForm" class="lead">
    <form class="search" th:action="@{/properties/new}" method="get">
        <input type="hidden" name="address" th:value="${address}">
        <input type="hidden" th:each="id : ${ownerIds}" name="owner" th:value="${id}">
        <input type="search" name="q" th:value="${term}" placeholder="Nazwisko albo imię">
        <button type="submit">Szukaj</button>
    </form>

    <p class="empty" th:if="${asked and hits.isEmpty()}">
        Nikogo takiego jeszcze nie znamy — wpisz dane poniżej.
    </p>

    <ul class="hits" th:unless="${hits.isEmpty()}">
        <li th:each="hit : ${hits}">
            <a th:href="@{/properties/new(address=${address}, owner=${ownerIds}, q=${term})}
                         + '&owner=' + ${hit.contactId()}"
               th:text="${hit.givenName()} + ' ' + ${hit.surname()} + ' — ' + ${hit.email()}">—</a>
        </li>
    </ul>

    <form th:action="@{/properties/new/owners}" method="post">
        <input type="hidden" name="address" th:value="${address}">
        <input type="hidden" th:each="id : ${ownerIds}" name="owner" th:value="${id}">

        <p class="separator">— albo nowy właściciel —</p>

        <div class="lead__new">
            <input type="text" name="givenName" placeholder="Imię" required>
            <input type="text" name="surname" placeholder="Nazwisko" required>
            <input type="email" name="email" placeholder="E-mail">
            <input type="tel" name="phone" placeholder="Telefon">
            <label>
                <input type="checkbox" name="infoClauseServed" value="true">
                Przekazano klauzulę informacyjną
            </label>
        </div>

        <button type="submit">Dodaj</button>
    </form>
</div>
</body>
</html>
```

- [ ] **Step 3: Write `property-new.html` (phase one branch only)**

Task 6 adds the `phase == 'shares'` branch to this same file. Write phase one now with the branch structure already in place so Task 6 only fills it in.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="pl"
      th:replace="~{layout :: page('Nowa nieruchomość — NAJEM', ~{::main})}">
<body>
<main>
    <h1>Nowa nieruchomość</h1>

    <p class="error" th:if="${error != null}" th:text="${error}">—</p>

    <div th:if="${phase == 'owners'}">
        <form class="search" th:action="@{/properties/new}" method="get">
            <input type="hidden" th:each="id : ${ownerIds}" name="owner" th:value="${id}">
            <label>
                Adres
                <input type="text" name="address" th:value="${address}" required autofocus
                       placeholder="np. Krucza 12/4, Warszawa">
            </label>
            <button type="submit">Zapamiętaj adres</button>
        </form>

        <h2>Właściciele</h2>

        <p class="empty" th:if="${owners.isEmpty()}">
            Nikt jeszcze nie jest wpisany jako właściciel.
        </p>

        <ul class="drafted" th:unless="${owners.isEmpty()}">
            <li th:each="owner : ${owners}">
                <span th:text="${owner.details().givenName()} + ' ' + ${owner.details().surname()}">—</span>
                <a th:href="@{/properties/new(address=${address}, owner=${owner.others()})}">Usuń</a>
            </li>
        </ul>

        <div th:insert="~{owners-form :: ownersForm}"></div>

        <p class="actions">
            <a class="button" th:unless="${owners.isEmpty()}"
               th:href="@{/properties/new(address=${address}, owner=${ownerIds}, owners='done')}">
                Dalej — udziały
            </a>
        </p>
    </div>
</main>
</body>
</html>
```

Note the address form is a GET back to `/properties/new` carrying the drafted owners: that is how a typed address becomes part of the URL so the picker's hidden fields can carry it. `required` on the address input means the browser will not submit it blank.

- [ ] **Step 4: Add the button to `properties.html`**

After `<h1>Nieruchomości</h1>`:

```html
    <p class="actions">
        <a class="button" th:href="@{/properties/new}">Dodaj nieruchomość</a>
    </p>
```

- [ ] **Step 5: Write the integration test (phase one)**

Create `apps/najem-app/src/test/java/pl/najem/app/web/AddPropertyScreenTest.java`, same preamble as Task 4's test but a third distinct `OPERATOR` UUID.

```java
    @Test
    void theportfolioBoardOffersTheAddButton() throws Exception {
        mvc.perform(get("/properties"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodaj nieruchomość")))
            .andExpect(content().string(containsString("/properties/new")));
    }

    /**
     * The picker fragment must appear EXACTLY ONCE. Asserted as a count, not as presence, because
     * the bug this catches — a th:fragment declared inline rendering both where it sits and where it
     * is inserted — is completely invisible to a present/absent assertion, and shipped once already.
     */
    @Test
    void theownerPickerIsRenderedOnce() throws Exception {
        String html = mvc.perform(get("/properties/new"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html.split("— albo nowy właściciel —", -1)).hasSize(2);
    }

    /**
     * The whole reason this screen is two phases. If the address does not survive the round trip,
     * the manager retypes it every time they look somebody up.
     */
    @Test
    void searchingForAnOwnerDoesNotLoseTheAddressAlreadyTyped() throws Exception {
        mvc.perform(get("/properties/new").param("address", "Krucza 12/4").param("q", "Kowalsk"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Krucza 12/4")));
    }

    @Test
    void anownerNobodyHasMetYetIsCreatedInlineAndAppearsInTheDraft() throws Exception {
        String redirect = mvc.perform(post("/properties/new/owners").with(csrf())
                .param("address", "Krucza 12/4")
                .param("givenName", "Anna").param("surname", "Kowalska")
                .param("email", "anna@example.com").param("infoClauseServed", "true"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).startsWith("/properties/new").contains("owner=");

        mvc.perform(get(redirect))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Anna Kowalska")))
            .andExpect(content().string(containsString("Krucza 12/4")));

        // Rule 12: the lawful basis is stored data and the value is the point of the decision.
        assertThat(jdbc.queryForObject(
            "select lawful_basis from contacts_person where workspace_id = ? and surname = ?",
            String.class, workspaceId, "Kowalska")).isEqualTo("contract");
    }

    @Test
    void thesamePersonCannotBeDraftedAsOwnerTwice() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null),
            true, LocalDate.now());

        mvc.perform(get("/properties/new")
                .param("address", "Krucza 12/4")
                .param("owner", anna.toString())
                .param("owner", anna.toString()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void agarbledOwnerIdIsRefusedRatherThanSilentlyDropped() throws Exception {
        mvc.perform(get("/properties/new").param("owner", "not-a-uuid"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void thenextStepIsOfferedOnlyOnceSomebodyOwnsTheProperty() throws Exception {
        mvc.perform(get("/properties/new").param("address", "Krucza 12/4"))
            .andExpect(content().string(not(containsString("Dalej — udziały"))));

        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null),
            true, LocalDate.now());

        mvc.perform(get("/properties/new").param("address", "Krucza 12/4")
                .param("owner", anna.toString()))
            .andExpect(content().string(containsString("Dalej — udziały")));
    }
```

`@Autowired ContactService contacts;` and `@Autowired JdbcTemplate jdbc;` are needed on the test class.

- [ ] **Step 6: Run it**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :apps:najem-app:test -PintegrationTests --tests '*AddPropertyScreenTest*'
```

Expected: PASS, 7 tests. Foreground, `timeout: 600000`.

- [ ] **Step 7: Mutate to prove the fragment-count test bites (rule 21)**

Temporarily move the `ownersForm` fragment's markup inline into `property-new.html` (declare `th:fragment="ownersForm"` on a div inside that page and insert it in the same page). Re-run. Expected: `theownerPickerIsRenderedOnce` goes RED with a size of 3, not 2. Revert and confirm green. Record both in your report.

- [ ] **Step 8: Run the fast tier and commit**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build
git add apps/najem-app/src/main/java/pl/najem/app/web/AddPropertyScreenController.java \
        apps/najem-app/src/main/resources/templates/property-new.html \
        apps/najem-app/src/main/resources/templates/owners-form.html \
        apps/najem-app/src/main/resources/templates/properties.html \
        apps/najem-app/src/test/java/pl/najem/app/web/AddPropertyScreenTest.java
git commit -m "Ask who owns the building before asking in what shares"
```

---

### Task 6: The add-property screen, phase two — shares, and the create

**Files:**
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/AddPropertyScreenController.java` (add the `owners=done` branch and the `POST /properties` handler)
- Modify: `apps/najem-app/src/main/resources/templates/property-new.html` (add the `phase == 'shares'` branch)
- Modify: `apps/najem-app/src/main/resources/templates/units.html` (render the flash warnings)
- Modify: `apps/najem-app/src/test/java/pl/najem/app/web/AddPropertyScreenTest.java`

**Interfaces:**
- Consumes: `OwnerDraft` (Task 3); `PortfolioService.CreatedProperty(UUID propertyId, List<String> warnings)` and `PortfolioService.createProperty(UUID, String, List<Owner>)` (Task 2); `pl.najem.pm.domain.Owner(UUID contactId, BigDecimal sharePercent)`; the phase-one controller's `named(...)` helper and `NamedOwner` record (Task 5).
- Produces: `POST /properties`, redirecting to `/properties/{id}/units`.

- [ ] **Step 1: Add the phase-two branch to the controller's GET**

In `form(...)`, replace the tail of the method:

```java
        if ("done".equals(phase)) {
            // Reached with nobody drafted means the manager typed the URL or went back after
            // removing the last owner. A property with no owner is a record of nothing and there is
            // no screen to add one afterwards, so this is a return to phase one, not a create.
            if (draft.owners().isEmpty()) {
                model.addAttribute("error", "Nieruchomość musi mieć co najmniej jednego właściciela.");
            } else if (address == null || address.isBlank()) {
                model.addAttribute("error", "Najpierw podaj adres nieruchomości.");
            } else {
                model.addAttribute("phase", "shares");
                model.addAttribute("term", null);
                model.addAttribute("asked", false);
                model.addAttribute("hits", List.of());
                return "property-new";
            }
        }

        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));
        model.addAttribute("phase", "owners");
        return "property-new";
```

- [ ] **Step 2: Add the create handler**

```java
    /**
     * The building is entered.
     *
     * <p>The shares arrive as a parallel list positionally matched to the owners, which is what the
     * template's inputs produce — the n-th share belongs to the n-th owner. A length mismatch means
     * the form was tampered with or a param was dropped in transit, and assigning the shares anyway
     * would silently give somebody else's stake to the wrong person. Refused rather than trimmed.
     *
     * <p>Ownership of each drafted contact is checked before anything commits, for the reason
     * {@link ReserveScreenController#create} learned: a drafted id can go stale between the GET that
     * last validated it and this POST, and a foreign or erased id must 404 here rather than end up
     * on a created property naming nobody.
     */
    @PostMapping("/properties")
    public String create(@RequestParam String address,
                         @RequestParam(name = "owner", required = false) List<String> owners,
                         @RequestParam(name = "share", required = false) List<BigDecimal> shares,
                         WebWorkspace workspace, Model model, RedirectAttributes flash) {
        UUID workspaceId = workspace.workspaceId();
        var draft = OwnerDraft.of(owners);
        if (draft.owners().isEmpty()) {
            throw new IllegalArgumentException("A property needs at least one owner");
        }
        if (shares == null || shares.size() != draft.owners().size()) {
            throw new IllegalArgumentException("Every owner needs a share");
        }
        draft.owners().forEach(id -> directory.requireIn(workspaceId, id));

        List<Owner> stakes = new ArrayList<>();
        for (int i = 0; i < draft.owners().size(); i++) {
            stakes.add(new Owner(draft.owners().get(i), shares.get(i)));
        }

        var created = portfolio.createProperty(workspaceId, address, stakes);
        flash.addFlashAttribute("warnings", created.warnings());
        return "redirect:/properties/" + created.propertyId() + "/units";
    }
```

Add imports: `java.math.BigDecimal`, `org.springframework.web.servlet.mvc.support.RedirectAttributes`, `pl.najem.pm.domain.Owner`.

- [ ] **Step 3: Add the phase-two branch to `property-new.html`**

Inside `<main>`, after the `phase == 'owners'` div:

```html
    <div th:if="${phase == 'shares'}">
        <p class="note">Adres: <strong th:text="${address}">—</strong></p>

        <form th:action="@{/properties}" method="post">
            <input type="hidden" name="address" th:value="${address}">

            <h2>Udziały</h2>
            <p class="hint">
                Udziały powinny sumować się do 100%. Jeśli nie sumują się, nieruchomość i tak
                zostanie zapisana — dostaniesz tylko ostrzeżenie.
            </p>

            <ul class="drafted">
                <li th:each="owner : ${owners}">
                    <input type="hidden" name="owner" th:value="${owner.contactId()}">
                    <span th:text="${owner.details().givenName()} + ' ' + ${owner.details().surname()}">—</span>
                    <input type="number" name="share" step="0.01" min="0" max="100" required
                           th:value="${#numbers.formatDecimal(100.0 / owners.size(), 1, 'NONE', 2, 'POINT')}">
                    <span>%</span>
                </li>
            </ul>

            <div class="actions">
                <button type="submit">Utwórz nieruchomość</button>
                <a class="button secondary"
                   th:href="@{/properties/new(address=${address}, owner=${ownerIds})}">Wróć</a>
            </div>
        </form>
    </div>
```

The prefilled even split is a suggestion, not a claim: two owners get 50.00 each, three get 33.33 each and the total comes to 99.99, which raises the warning the manager then reads and fixes. That is the correct behaviour — the system does not quietly round somebody's stake up to make its own arithmetic tidy. The separator is `'POINT'` because this value goes back into a `type="number"` input, which is wire data, not display.

- [ ] **Step 4: Render the warnings on the landing screen**

`units.html` is where the manager lands. Add after `<h1>Lokale</h1>` and before the "Dodaj lokal" button:

```html
    <ul class="warnings" th:if="${warnings != null and !warnings.isEmpty()}">
        <li th:each="warning : ${warnings}" th:text="${warning}">—</li>
    </ul>
```

Check `reserve-terms.html` / `timeline.html` first: if a `warnings` block and a `.warnings` CSS class already exist somewhere, copy that markup exactly rather than inventing a second style for the same thing.

The warning text itself comes from `Property.warnings()` and is English ("Ownership shares total 90, expected 100"). Leave it English in the domain — that is the established split — and do NOT translate it in the template by string-matching. If a Polish version is wanted it needs a structured warning type, and that is not this change. Note it in your report as a known gap.

- [ ] **Step 5: Write the phase-two tests**

Append to `AddPropertyScreenTest`:

```java
    @Test
    void apropertyWithTwoOwnersAtFiftyFiftyIsCreatedWithoutWarnings() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", null), true, LocalDate.now());

        String redirect = mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12/4, Warszawa")
                .param("owner", anna.toString()).param("share", "50")
                .param("owner", piotr.toString()).param("share", "50"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).matches("/properties/[0-9a-f-]{36}/units");

        projections.runOnce();
        mvc.perform(get(redirect))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Dodaj lokal")));

        assertThat(jdbc.queryForObject("select count(*) from pm_property where address = ?",
            Integer.class, "Krucza 12/4, Warszawa")).isEqualTo(1);
    }

    /**
     * Created ANYWAY, and warned. Asserting only the warning would pass equally against an
     * implementation that refused the write, which is the opposite of the decision taken.
     */
    @Test
    void sharesThatDoNotAddUpStillCreateThePropertyAndSaySo() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", null), true, LocalDate.now());

        var result = mvc.perform(post("/properties").with(csrf())
                .param("address", "Wilcza 3")
                .param("owner", anna.toString()).param("share", "60")
                .param("owner", piotr.toString()).param("share", "30"))
            .andExpect(status().is3xxRedirection())
            .andReturn();

        @SuppressWarnings("unchecked")
        var warnings = (List<String>) result.getFlashMap().get("warnings");
        assertThat(warnings).singleElement().asString().contains("90");

        assertThat(jdbc.queryForObject("select count(*) from pm_property where address = ?",
            Integer.class, "Wilcza 3")).isEqualTo(1);
    }

    @Test
    void thesharesPhaseListsEveryDraftedOwnerWithAnInput() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", null), true, LocalDate.now());

        String html = mvc.perform(get("/properties/new")
                .param("address", "Krucza 12/4")
                .param("owner", anna.toString()).param("owner", piotr.toString())
                .param("owners", "done"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Anna Kowalska").contains("Piotr Nowak");
        assertThat(html.split("name=\"share\"", -1)).hasSize(3);
        // The even split is prefilled as a suggestion.
        assertThat(html).contains("value=\"50.00\"");
        // Phase two shows no picker — the owner list is settled.
        assertThat(html).doesNotContain("— albo nowy właściciel —");
    }

    @Test
    void reachingTheSharesPhaseWithNobodyDraftedGoesBackAndSaysWhy() throws Exception {
        mvc.perform(get("/properties/new").param("address", "Krucza 12/4").param("owners", "done"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("co najmniej jednego właściciela")))
            .andExpect(content().string(containsString("— albo nowy właściciel —")));
    }

    @Test
    void ashareListShorterThanTheOwnerListIsRefused() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null), true, LocalDate.now());
        UUID piotr = contacts.registerParty(workspaceId,
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12/4")
                .param("owner", anna.toString()).param("owner", piotr.toString())
                .param("share", "100"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anownerFromAnotherAgencyIsNotFound() throws Exception {
        UUID theirs = contacts.registerParty(otherWorkspaceId,
            new ContactDetails("Nie", "Nasza", "x@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "Krucza 12/4")
                .param("owner", theirs.toString()).param("share", "100"))
            .andExpect(status().isNotFound());
    }

    @Test
    void apropertyWithNoAddressIsRefused() throws Exception {
        UUID anna = contacts.registerParty(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", null), true, LocalDate.now());

        mvc.perform(post("/properties").with(csrf())
                .param("address", "   ")
                .param("owner", anna.toString()).param("share", "100"))
            .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 6: Run it**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :apps:najem-app:test -PintegrationTests --tests '*AddPropertyScreenTest*'
```

Expected: PASS, 14 tests. Foreground, `timeout: 600000`.

- [ ] **Step 7: Mutate to prove the pre-commit ownership check bites (rule 21)**

Temporarily delete the `draft.owners().forEach(id -> directory.requireIn(workspaceId, id));` line. Re-run. Expected: `anownerFromAnotherAgencyIsNotFound` goes RED — the property is created with a foreign owner and the response is a 302, not a 404. Revert and confirm green. Record both in your report.

- [ ] **Step 8: Run both tiers and commit**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build
./gradlew build -PintegrationTests
git add apps/najem-app/src/main/java/pl/najem/app/web/AddPropertyScreenController.java \
        apps/najem-app/src/main/resources/templates/property-new.html \
        apps/najem-app/src/main/resources/templates/units.html \
        apps/najem-app/src/main/resources/static/css/najem.css \
        apps/najem-app/src/test/java/pl/najem/app/web/AddPropertyScreenTest.java
git commit -m "Take the shares, create the building, land on its empty unit list"
```

---

## Self-review notes

**Spec coverage.** Routes → Tasks 4/5/6. Two-phase rationale → Task 5 javadoc. Save-and-add-another → Task 4. Domain guards → Task 1. `CreatedProperty` → Task 2. `OwnerDraft` → Task 3. Separate fragment file → Task 5 step 2 plus the count assertion. Amounts inherited → Global Constraints plus Task 4's template. Error table → Tasks 4 step 4, 5 step 5, 6 step 5. Freshness → nothing to build; `ProjectionFreshnessAdvice` already covers it, which is why no task mentions it. Every test the spec lists appears in a task.

**Known gap, declared rather than hidden.** The share warning renders in English ("Ownership shares total 90, expected 100") because it is a domain string, and translating it in the template by string-matching would be worse than leaving it. Task 6 step 4 says so explicitly and asks the implementer to report it. Fixing it properly needs a structured warning type across all of `Warnings`, which is a different change.

**One thing deliberately not built:** there is no remove-a-unit, edit-address, or change-shares screen. Everything here is create-only, matching the spec.
