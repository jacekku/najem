# Unit interest screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a manager a unit screen where they can record that somebody phoned about that unit, creating the person if the agency does not already know them.

**Architecture:** A new screen at `GET /units/{unitId}` in `apps/najem-app`, reading unit facts from Reporting's `UnitBoardQuery` and the interested people from a new `UnitInterestQuery` port in the contacts module. Writes go through the existing `ContactService` / `InterestService` as plain form POSTs that redirect, matching the bank screen. No new tables, no new migrations, no schema change.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Thymeleaf, plain `JdbcTemplate` in adapters, JUnit 5 + AssertJ, Testcontainers for the slow tier.

The spec is `docs/superpowers/specs/2026-08-07-unit-interest-screen-design.md`. Read it before Task 1.

## Global Constraints

- **`JAVA_HOME` must be Java 21.** On this machine: `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`. The `JAVA_HOME` already in the environment points at JDK 19 and Gradle will use it.
- **Two test tiers.** `./gradlew build` (~45s) is the inner loop and excludes containers. `./gradlew build -PintegrationTests` (~8min) is what a merge runs. Any class that boots a container carries `@Tag("integration")`. When the two tiers disagree, the database is right and the in-memory mirror is wrong.
- **The domain imports nothing outside the JDK** (architecture rule A3). Nothing in this plan touches a `domain` package.
- **`application` must not import `adapter`** (refactoring rule 4). The mechanical check is `grep -r "pl\.najem\.contacts\.adapter" modules/contacts/src/main/java/pl/najem/contacts/application` returning nothing.
- **Every driven port gets a real implementation and an in-memory double** (rule A5). This plan adds one port and both.
- **A fake models the mechanism, not the outcome** (rule 14). The in-memory `UnitInterestQuery` performs the join; it must not be handed the answer.
- **The contacts module never reads a clock.** `WallClockTest` asserts it. Every date arrives as an argument. A `LocalDate.now()` anywhere in `modules/contacts/src/main` will turn that test red.
- **Polish copy, and number formats are stated not inherited.** Amounts render with `${#numbers.formatDecimal(x, 1, 'WHITESPACE', 2, 'COMMA')}` — never bare `th:text` on a `BigDecimal`, because Thymeleaf's default takes the separator from the server locale.
- **Commit as the current user.** `git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" commit`. Do not sign as Claude and do not mention an LLM in the message.
- **Messages on non-ASCII from Git Bash.** `git commit -m "…"` with Polish characters is mangled by argv conversion on this machine. Write the message to a file and use `git commit -F <file>`, or keep the subject ASCII.

---

### Task 1: A single-unit read in Reporting

The screen needs one unit's name, rent, market state and occupancy. `UnitBoardQuery.forProperty` already computes all of it for a whole property; this adds the one-row form.

**Files:**
- Modify: `modules/reporting/src/main/java/pl/najem/reporting/application/UnitBoardQuery.java`
- Test: `modules/reporting/src/test/java/pl/najem/reporting/application/BoardQueriesTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Optional<UnitBoardQuery.Row> forUnit(UUID workspaceId, UUID unitId, LocalDate asOf)`. `Row` is the existing `record Row(UUID unitId, UUID propertyId, String name, BigDecimal baseRent, String marketState, UUID currentTenancyId, UUID nextTenancyId)`.

`BoardQueriesTest` is the right home and its javadoc says why: `PropertyBoardQuery` and `UnitBoardQuery` once drifted into two answers about whether a completed tenancy counts, and the class exists so one fixture catches that. `forUnit` and `forProperty` are now a third pair that can drift, so the test asserts they agree rather than asserting `forUnit` in isolation.

- [ ] **Step 1: Write the failing test**

Append to `BoardQueriesTest`. Its `@BeforeAll` already builds the fixture; the fields used below are `workspace`, `otherWorkspace`, `letProperty` and `units`, all static and all already there.

```java
    /**
     * forUnit and forProperty are one predicate in two queries, which is exactly the pair that
     * drifted before (see this class's header). Asserting agreement rather than asserting forUnit
     * alone is what makes the second copy safe to have.
     */
    @Test
    void forUnitAgreesWithTheRowForPropertyReturnsForTheSameUnit() {
        var asOf = LocalDate.of(2026, 3, 1);
        var fromList = units.forProperty(workspace, letProperty, asOf);

        assertThat(fromList).hasSize(3);
        for (var row : fromList) {
            assertThat(units.forUnit(workspace, row.unitId(), asOf)).contains(row);
        }
    }

    /**
     * The fixture's other agency owns a unit named "m. 2" on purpose, so this cannot pass by the
     * id simply not existing.
     */
    @Test
    void forUnitIsEmptyForAUnitInAnotherWorkspace() {
        var asOf = LocalDate.of(2026, 3, 1);

        assertThat(units.forUnit(workspace, otherUnit, asOf)).isEmpty();
        assertThat(units.forUnit(otherWorkspace, otherUnit, asOf)).isPresent();
    }

    /**
     * 2026-03-01 is inside the let tenancy and after the ended one, which is the date the rest of
     * this class uses; asserting occupancy here too means forUnit is checked against the same
     * period predicate the list is, not merely against the same columns.
     */
    @Test
    void forUnitResolvesTheCurrentTenancyLikeTheListDoes() {
        var row = units.forUnit(workspace, occupiedUnit, LocalDate.of(2026, 3, 1));

        assertThat(row).isPresent();
        assertThat(row.get().currentTenancyId()).isEqualTo(currentTenancy);
    }
```

- [ ] **Step 2: Run the test and watch it fail**

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :modules:reporting:test -PintegrationTests --tests '*BoardQueriesTest*'
```

Expected: compilation failure — `cannot find symbol: method forUnit`.

- [ ] **Step 3: Add `forUnit`**

In `UnitBoardQuery`, after `forProperty`. The two correlated sub-selects are copied verbatim; only the outer `where` changes.

```java
/**
 * One unit, for the screen that shows one unit.
 *
 * <p>The warning above is about a board calling a per-unit primitive N times, and it stands. This
 * is the other case: a single-unit screen calling it once. Reaching for {@link #forProperty} and
 * filtering would read a property's worth of rows to render one of them, and would need the
 * property id the caller does not have.
 *
 * <p>Empty rather than an exception for unknown or foreign, matching every other read here: a
 * caller cannot distinguish "no such unit" from "not yours", which is the point.
 */
public Optional<Row> forUnit(UUID workspaceId, UUID unitId, LocalDate asOf) {
    return jdbc.query("""
        select u.unit_id, u.property_id, u.name, u.base_rent, u.market_state,
          (select p.tenancy_id from reporting_unit_period p
             where p.workspace_id = u.workspace_id and p.unit_id = u.unit_id
               and not p.annulled and (not p.released or p.ended_on is not null)
               and p.starts_on <= ? and (p.ends_on is null or p.ends_on > ?)
             order by p.starts_on limit 1) as current_tenancy_id,
          (select p.tenancy_id from reporting_unit_period p
             where p.workspace_id = u.workspace_id and p.unit_id = u.unit_id
               and not p.annulled and (not p.released or p.ended_on is not null)
               and p.starts_on > ?
             order by p.starts_on limit 1) as next_tenancy_id
        from reporting_unit_state u
        where u.workspace_id = ? and u.unit_id = ? and not u.removed
        """,
        (rs, i) -> new Row(
            UUID.fromString(rs.getString("unit_id")),
            UUID.fromString(rs.getString("property_id")),
            rs.getString("name"),
            rs.getBigDecimal("base_rent"),
            rs.getString("market_state"),
            uuidOrNull(rs.getString("current_tenancy_id")),
            uuidOrNull(rs.getString("next_tenancy_id"))),
        asOf, asOf, asOf, workspaceId, unitId)
        .stream().findFirst();
}
```

Add `import java.util.Optional;` at the top.

- [ ] **Step 4: Run the test and watch it pass**

```
./gradlew :modules:reporting:test -PintegrationTests --tests '*BoardQueriesTest*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/reporting/src/main/java/pl/najem/reporting/application/UnitBoardQuery.java \
        modules/reporting/src/test/java/pl/najem/reporting/application/BoardQueriesTest.java
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Ask the unit board for one unit, and prove it answers what the list does"
```

---

### Task 2: `UnitInterestQuery` — who is interested, with their names

The interest rows carry a `contactId` and nothing else. The screen needs names, and they can only come from the contacts module: Reporting has never seen one, because the PII lookaside keeps names out of events, and a projection holding a name would outlive the `contacts_person` deletion that *is* erasure.

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestedParty.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/UnitInterestQuery.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/adapter/persistence/PostgresUnitInterestQuery.java`
- Create: `modules/contacts/src/test/java/pl/najem/contacts/application/InMemoryUnitInterests.java`
- Modify: `modules/contacts/src/testFixtures/java/pl/najem/contacts/adapter/persistence/PostgresContacts.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactRulesTest.java` (fast)
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/InterestServiceTest.java` (container)

**Interfaces:**
- Consumes: `InterestRepository`, `ContactRepository` (both exist).
- Produces:
  - `record InterestedParty(UUID interestId, UUID contactId, String givenName, String surname, String email, String phone, BigDecimal willingToPay, LocalDate desiredStart)`
  - `interface UnitInterestQuery { List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId); }`
  - `PostgresContacts.unitInterestQuery(JdbcTemplate jdbc)` returning `UnitInterestQuery`

- [ ] **Step 1: Write the failing fast test**

Append to `ContactRulesTest`. Its `@BeforeEach` already builds `people`, `interestRows`, `directory`, `contacts` and `interests`; add one field and one line to that method:

```java
    private UnitInterestQuery interestedParties;
```

and at the end of `setUp()`:

```java
        interestedParties = new InMemoryUnitInterests(interestRows, people);
```

Then the tests:

```java
    // ---- who is interested in a unit ---------------------------------------

    @Test
    void anInterestedPartyCarriesTheNameFromThePersonTable() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        var interestId = interests.register(AGENCY, contactId, unit,
            new BigDecimal("2900.00"), LocalDate.of(2026, 9, 1));

        assertThat(interestedParties.activeForUnit(AGENCY, unit)).containsExactly(
            new InterestedParty(interestId, contactId, "Anna", "Kowalska",
                "anna@example.com", "+48600100200",
                new BigDecimal("2900.00"), LocalDate.of(2026, 9, 1)));
    }

    @Test
    void awithdrawnInterestLeavesTheUnitsList() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        var interestId = interests.register(AGENCY, contactId, unit, null, null);

        interests.withdraw(AGENCY, interestId, TODAY);

        assertThat(interestedParties.activeForUnit(AGENCY, unit)).isEmpty();
    }

    /**
     * The join is an inner one, and this is the assertion that says so. Erasure deletes the person
     * row and the interests together, so the two are normally consistent — but a double that
     * carried its own copy of the name would keep answering after the person was gone, which is
     * the one failure a PII lookaside exists to prevent.
     */
    @Test
    void anerasedPersonIsNotAnInterestedParty() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        interests.register(AGENCY, contactId, unit, null, null);

        contacts.erase(AGENCY, contactId, TODAY);

        assertThat(interestedParties.activeForUnit(AGENCY, unit)).isEmpty();
    }

    @Test
    void aninterestInanotherWorkspacesUnitIsNotVisible() {
        var unit = UUID.randomUUID();
        var contactId = anna(AGENCY);
        interests.register(AGENCY, contactId, unit, null, null);

        assertThat(interestedParties.activeForUnit(OTHER, unit)).isEmpty();
    }
```

- [ ] **Step 2: Run it and watch it fail**

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :modules:contacts:test --tests '*ContactRulesTest*'
```

Expected: compilation failure — `cannot find symbol: class InterestedParty` / `class InMemoryUnitInterests`.

- [ ] **Step 3: Write the record**

`modules/contacts/src/main/java/pl/najem/contacts/application/InterestedParty.java`:

```java
package pl.najem.contacts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of "who wants this unit" — the interest and the person in one answer.
 *
 * <p>{@code willingToPay} and {@code desiredStart} are nullable. Somebody who rings to ask what the
 * agency would take for a flat has named neither, and a screen that demanded both would either
 * refuse to record the call or invent a number.
 */
public record InterestedParty(UUID interestId, UUID contactId, String givenName, String surname,
                              String email, String phone, BigDecimal willingToPay,
                              LocalDate desiredStart) {

    /** For a template, which should not be assembling a person's name from parts. */
    public String fullName() {
        return givenName + " " + surname;
    }
}
```

- [ ] **Step 4: Write the port**

`modules/contacts/src/main/java/pl/najem/contacts/application/UnitInterestQuery.java`:

```java
package pl.najem.contacts.application;

import java.util.List;
import java.util.UUID;

/**
 * The active interests in one unit, each already carrying the person's name.
 *
 * <p><b>{@code Query}, not {@code Repository} and not {@code Projection}</b> — architecture.md's
 * third suffix, on the same grounds as {@code SuggestionQuery}. It joins {@code contacts_interest}
 * to {@code contacts_person}, two tables this module already owns; there is no denormalized store
 * to rebuild, so {@code Projection} would claim something about storage that is not true, and
 * {@code Repository} is taken by {@link InterestRepository}, which holds the record.
 *
 * <p><b>Why it exists at all, rather than the caller pairing {@link InterestService#forUnit} with
 * {@link ContactDirectory#find}.</b> That is an N+1 in a controller — the fan-out
 * {@code UnitBoardQuery}'s javadoc warns about — and the alternative of projecting names into
 * Reporting is barred outright: the PII lookaside means Reporting has never seen a name, and a
 * projected copy would survive the {@code contacts_person} deletion that <em>is</em> erasure. See
 * {@link ContactDirectory#search}, which is here for the same reason.
 *
 * <p>Active only. A withdrawn interest is a fact the stream keeps and a screen does not show.
 */
public interface UnitInterestQuery {

    List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId);
}
```

- [ ] **Step 5: Write the in-memory double**

`modules/contacts/src/test/java/pl/najem/contacts/application/InMemoryUnitInterests.java`. It **composes the two existing doubles and performs the join**, the way `InMemoryErasureDue` composes `InMemoryContacts` and `InMemoryRetentionHolds`. It must not hold its own copy of a name (rule 14).

```java
package pl.najem.contacts.application;

import java.util.List;
import java.util.UUID;

/**
 * The join, done the way the SQL does it (rule 14).
 *
 * <p>It stores nothing. It asks the interest double for the active rows and the people double for
 * each name, which is what makes it an <em>inner</em> join: a contact whose row is gone drops out,
 * exactly as {@code join contacts_person} drops it. A double that cached the name at registration
 * would keep answering after erasure — and would look identical to this one in every test that
 * never erases anybody, which is how a fake starts lying.
 *
 * <p>What it does not model is ordering under the database's collation; the SQL orders by surname
 * and this preserves the interest double's order. A test asserting Ł against L belongs in the
 * container tier (rule 16).
 */
public class InMemoryUnitInterests implements UnitInterestQuery {

    private final InterestRepository interests;
    private final ContactRepository people;

    public InMemoryUnitInterests(InterestRepository interests, ContactRepository people) {
        this.interests = interests;
        this.people = people;
    }

    @Override
    public List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId) {
        return interests.activeForUnit(workspaceId, unitId).stream()
            .flatMap(interest -> people.find(workspaceId, interest.contactId()).stream()
                .map(details -> new InterestedParty(interest.interestId(), interest.contactId(),
                    details.givenName(), details.surname(), details.email(), details.phone(),
                    interest.willingToPay(), interest.desiredStart())))
            .toList();
    }
}
```

- [ ] **Step 6: Run the fast test and watch it pass**

```
./gradlew :modules:contacts:test --tests '*ContactRulesTest*'
```

Expected: PASS. If `OTHER` is not already a constant in `ContactRulesTest`, it is — check the top of the file; it is declared beside `AGENCY`.

- [ ] **Step 7: Write the Postgres adapter**

`modules/contacts/src/main/java/pl/najem/contacts/adapter/persistence/PostgresUnitInterestQuery.java`:

```java
package pl.najem.contacts.adapter.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import pl.najem.contacts.application.InterestedParty;
import pl.najem.contacts.application.UnitInterestQuery;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The join in one statement.
 *
 * <p>An inner join, deliberately: an interest whose person has been erased is not a row a screen
 * may show, and erasure deletes both — so the join being inner is the belt to that braces.
 *
 * <p>Both tables are filtered on the workspace. The interest side alone would be enough today
 * because interests carry the same workspace as their contact, but a join that scopes one side and
 * trusts the other is a scope that holds by coincidence.
 */
@Repository
public class PostgresUnitInterestQuery implements UnitInterestQuery {

    private final JdbcTemplate jdbc;

    public PostgresUnitInterestQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<InterestedParty> activeForUnit(UUID workspaceId, UUID unitId) {
        return jdbc.query("""
            select i.interest_id, i.contact_id, p.given_name, p.surname, p.email, p.phone,
                   i.willing_to_pay, i.desired_start
            from contacts_interest i
            join contacts_person p
              on p.contact_id = i.contact_id and p.workspace_id = i.workspace_id
            where i.workspace_id = ? and i.unit_id = ? and i.status = 'active'
            order by p.surname, p.given_name, i.interest_id
            """,
            (rs, i) -> new InterestedParty(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBigDecimal(7), rs.getObject(8, LocalDate.class)),
            workspaceId, unitId);
    }
}
```

- [ ] **Step 8: Add it to the test-fixtures wiring**

In `PostgresContacts`, after `interestService`:

```java
    public static UnitInterestQuery unitInterestQuery(JdbcTemplate jdbc) {
        return new PostgresUnitInterestQuery(jdbc);
    }
```

and `import pl.najem.contacts.application.UnitInterestQuery;` at the top.

- [ ] **Step 9: Write the container test**

Append to `InterestServiceTest` — it already has a migrated database and a real `InterestService`. Read its `@BeforeAll` for the names of its fixture fields and reuse them; it will have a `jdbc`, a `store` and an `AGENCY`. Register the person through the real `ContactService` (obtain it with `PostgresContacts.contactService(store, jdbc)` if the class does not already hold one) so the row is written the way production writes it.

```java
    /**
     * The half the in-memory mirror cannot judge: the join and the ordering, as Postgres runs them.
     * Rule 15 — the SQL is the thing that changed, so the SQL is what gets tested.
     */
    @Test
    void listsInterestedPartiesByNameWithTheirDetails() {
        var unit = UUID.randomUUID();
        var contacts = PostgresContacts.contactService(store, jdbc);
        var query = PostgresContacts.unitInterestQuery(jdbc);

        var zielinski = contacts.register(new NewContact(AGENCY,
            new ContactDetails("Tomasz", "Zielinski", "t.z@example.com", "+48500000001"),
            "legitimate-interest", null, null));
        var kowalska = contacts.register(new NewContact(AGENCY,
            new ContactDetails("Anna", "Kowalska", "a.k@example.com", "+48500000002"),
            "legitimate-interest", null, null));

        service.register(AGENCY, zielinski, unit, new BigDecimal("3000.00"), LocalDate.of(2026, 9, 1));
        service.register(AGENCY, kowalska, unit, null, null);

        assertThat(query.activeForUnit(AGENCY, unit))
            .extracting(InterestedParty::surname, InterestedParty::willingToPay)
            .containsExactly(
                tuple("Kowalska", null),
                tuple("Zielinski", new BigDecimal("3000.00")));
    }
```

Use whatever the class calls its `InterestService` field in place of `service`. Add `import static org.assertj.core.api.Assertions.tuple;` and imports for `NewContact`, `ContactDetails`, `InterestedParty`, `PostgresContacts`, `BigDecimal`.

Surnames are ASCII here on purpose: ordering Polish diacritics under the container's collation is a different assertion, and `InMemoryContacts`'s javadoc already records that the two tiers legitimately disagree about it.

- [ ] **Step 10: Run both tiers**

```
./gradlew :modules:contacts:test --tests '*ContactRulesTest*'
./gradlew :modules:contacts:test -PintegrationTests --tests '*InterestServiceTest*'
```

Expected: both PASS.

- [ ] **Step 11: Check the layering did not invert**

```
grep -r "pl\.najem\.contacts\.adapter" modules/contacts/src/main/java/pl/najem/contacts/application/
```

Expected: no output.

- [ ] **Step 12: Commit**

```bash
git add modules/contacts/src/main/java/pl/najem/contacts/application/InterestedParty.java \
        modules/contacts/src/main/java/pl/najem/contacts/application/UnitInterestQuery.java \
        modules/contacts/src/main/java/pl/najem/contacts/adapter/persistence/PostgresUnitInterestQuery.java \
        modules/contacts/src/test/java/pl/najem/contacts/application/InMemoryUnitInterests.java \
        modules/contacts/src/test/java/pl/najem/contacts/application/ContactRulesTest.java \
        modules/contacts/src/test/java/pl/najem/contacts/application/InterestServiceTest.java \
        modules/contacts/src/testFixtures/java/pl/najem/contacts/adapter/persistence/PostgresContacts.java
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Ask one question for who wants a unit, instead of N+1"
```

---

### Task 3: `ContactService.registerLead`

A person who phones about a flat is registered under `legitimate-interest`. Which basis that is, is the contacts module's decision and not a screen's.

**Files:**
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactRulesTest.java`

**Interfaces:**
- Consumes: `ContactService.register(NewContact)`, `ContactDetails`, `NewContact`.
- Produces: `UUID ContactService.registerLead(UUID workspaceId, ContactDetails details, boolean infoClauseServed, LocalDate today)`.

- [ ] **Step 1: Write the failing test**

Append to `ContactRulesTest`:

```java
    // ---- registering the person who phoned ---------------------------------

    @Test
    void aleadIsRegisteredUnderLegitimateInterest() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), true, TODAY);

        assertThat(store.load(contactId, "Contact").events()).containsExactly(
            new ContactRegistered(AGENCY, contactId, "legitimate-interest", TODAY, null));
    }

    /**
     * The clause is the one fact on that form which is the manager's to assert. Recording today
     * regardless would have the application claim it was read out when nobody said so.
     */
    @Test
    void anunservedInfoClauseIsRecordedAsAbsentRatherThanAsToday() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), false, TODAY);

        assertThat(store.load(contactId, "Contact").events()).containsExactly(
            new ContactRegistered(AGENCY, contactId, "legitimate-interest", null, null));
    }

    @Test
    void aleadIsFindableInTheDirectoryLikeAnyOtherPerson() {
        var contactId = contacts.registerLead(AGENCY,
            new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"), true, TODAY);

        assertThat(directory.find(AGENCY, contactId))
            .contains(new ContactDetails("Piotr", "Nowak", "p.nowak@example.com", "+48500000000"));
    }
```

`ContactRegistered` is already imported in that file? It imports `ContactErased` and `InterestWithdrawn`; add `import pl.najem.contacts.domain.ContactRegistered;`.

- [ ] **Step 2: Run it and watch it fail**

```
./gradlew :modules:contacts:test --tests '*ContactRulesTest*'
```

Expected: compilation failure — `cannot find symbol: method registerLead`.

- [ ] **Step 3: Implement it**

In `ContactService`, immediately after `register`:

```java
    /**
     * Somebody who phoned about a unit.
     *
     * <p>The basis is fixed here rather than asked for at the edge, per refactoring rule 10: what
     * basis a lead is registered under is this module's decision, and a constant in a controller
     * would be answered again — possibly differently — by the next screen that registers one.
     * {@code legitimate-interest} is what {@code ContactLifecycleTest} has always used for this kind
     * of person; a tenant is {@code contract}, and the two must not be merged.
     *
     * <p>{@code retainUntil} is deliberately null. How long an agency keeps a lead it never let to
     * is a retention policy nobody has decided, and inventing one here would put a date in the
     * erasure queue that no rule stands behind.
     *
     * <p>{@code today} is a parameter because this module reads no clock — {@code WallClockTest}
     * asserts it — and because the served date is the caller's fact, not the store's.
     */
    public UUID registerLead(UUID workspaceId, ContactDetails details,
                             boolean infoClauseServed, LocalDate today) {
        return register(new NewContact(workspaceId, details, "legitimate-interest",
            infoClauseServed ? today : null, null));
    }
```

- [ ] **Step 4: Run it and watch it pass**

```
./gradlew :modules:contacts:test --tests '*ContactRulesTest*'
./gradlew :modules:contacts:test --tests '*WallClockTest*'
```

Expected: both PASS. `WallClockTest` is the one that would go red if a `LocalDate.now()` slipped in.

- [ ] **Step 5: Commit**

```bash
git add modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java \
        modules/contacts/src/test/java/pl/najem/contacts/application/ContactRulesTest.java
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Let the module say what basis a lead is registered under"
```

---

### Task 4: The unit screen, read-only

The screen exists and shows the unit and who is interested. No form yet — that is Task 5.

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/UnitScreenController.java`
- Create: `apps/najem-app/src/main/resources/templates/unit.html`
- Modify: `apps/najem-app/src/main/resources/templates/units.html:23`
- Modify: `apps/najem-app/src/main/resources/static/css/najem.css` (append)

**Interfaces:**
- Consumes: `UnitBoardQuery.forUnit` (Task 1), `UnitInterestQuery.activeForUnit` (Task 2), `WebWorkspace`.
- Produces: `GET /units/{unitId}` rendering `unit.html` with model attributes `unit` (a `UnitBoardQuery.Row`) and `interested` (a `List<InterestedParty>`).

No security configuration is needed: `SecurityConfig` ends `anyRequest().authenticated()`, and only `/login`, `/css/**`, `/vendor/**` and the favicons are exempt.

- [ ] **Step 1: Write the controller**

```java
package pl.najem.app.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.reporting.application.UnitBoardQuery;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One unit, and the people who have asked about it.
 *
 * <p>The unit's facts come from Reporting and the people from Contacts, and the two cannot be
 * merged into one read: Reporting has never seen a name, because the PII lookaside keeps names out
 * of the events it projects from. That is not an inconvenience to route around — it is what makes
 * erasure complete, and a projection holding a name would outlive the deletion.
 *
 * <p>Renders what it is given. Whether a unit is let, and which of the three market states it is
 * in, are the projection's answers.
 */
@Controller
public class UnitScreenController {

    private final UnitBoardQuery units;
    private final UnitInterestQuery interested;
    private final Clock clock;

    public UnitScreenController(UnitBoardQuery units, UnitInterestQuery interested, Clock clock) {
        this.units = units;
        this.interested = interested;
        this.clock = clock;
    }

    @GetMapping("/units/{unitId}")
    public String unit(@PathVariable UUID unitId, WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        // Unknown and foreign are the same 404, as they are everywhere else here: an id that
        // answers differently for a unit in another agency tells a caller it exists.
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("unit", unit);
        model.addAttribute("interested", interested.activeForUnit(workspaceId, unitId));
        return "unit";
    }
}
```

- [ ] **Step 2: Write the template**

`apps/najem-app/src/main/resources/templates/unit.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="pl"
      th:replace="~{layout :: page('Lokal — NAJEM', ~{::main})}">
<body>
<main>
    <p class="crumb">
        <a th:href="@{/properties/{id}/units(id=${unit.propertyId()})}">← Lokale</a>
    </p>

    <h1 th:text="${unit.name()}">—</h1>

    <p class="unit__facts">
        <!--/*
          Stated format, not inherited: Thymeleaf's default takes the separator from the server
          locale, so the same code renders 2,850.00 on one machine and 2 850,00 on another with
          tests green either way. Same reason as the units table.
        */-->
        <span class="amount"
              th:text="${#numbers.formatDecimal(unit.baseRent(), 1, 'WHITESPACE', 2, 'COMMA')}">—</span>
        <!--/*
          Marketing, not occupancy — the two must never be merged. A let unit is legitimately still
          `inventory` because nobody is advertising it; whether it is let is the next span.
        */-->
        <span class="state" th:classappend="'state--' + ${unit.marketState()}"
              th:switch="${unit.marketState()}">
            <span th:case="'open'">Ogłoszony</span>
            <span th:case="'closed'">Wycofany z oferty</span>
            <span th:case="'inventory'">Nieogłoszony</span>
            <span th:case="*" th:text="${unit.marketState()}">—</span>
        </span>
        <a th:if="${unit.currentTenancyId() != null}"
           th:href="@{/tenancies/{id}/timeline(id=${unit.currentTenancyId()})}">Najem bieżący</a>
        <span th:if="${unit.currentTenancyId() == null}" class="vacant">Pustostan</span>
        <a th:if="${unit.nextTenancyId() != null}" class="upcoming"
           th:href="@{/tenancies/{id}/timeline(id=${unit.nextTenancyId()})}">· nadchodzący</a>
    </p>

    <section>
        <h2>Zainteresowani <span th:text="'(' + ${interested.size()} + ')'">(0)</span></h2>

        <p class="empty" th:if="${interested.isEmpty()}">
            Nikt jeszcze nie pytał o ten lokal.
        </p>

        <table class="board" th:unless="${interested.isEmpty()}">
            <thead>
            <tr>
                <th>Osoba</th>
                <th>Kontakt</th>
                <th class="amount">Gotów płacić</th>
                <th>Od kiedy</th>
            </tr>
            </thead>
            <tbody>
            <tr th:each="party : ${interested}">
                <td th:text="${party.fullName()}">—</td>
                <td>
                    <span th:text="${party.email()}">—</span>
                    <span class="muted" th:text="${party.phone()}">—</span>
                </td>
                <!--/*
                  Both are nullable: somebody who rings to ask what the agency would take has named
                  neither, and a dash is the honest rendering of a question that was not answered.
                */-->
                <td class="amount">
                    <span th:if="${party.willingToPay() != null}"
                          th:text="${#numbers.formatDecimal(party.willingToPay(), 1, 'WHITESPACE', 2, 'COMMA')}">—</span>
                    <span th:if="${party.willingToPay() == null}" class="muted">—</span>
                </td>
                <td>
                    <span th:if="${party.desiredStart() != null}" th:text="${party.desiredStart()}">—</span>
                    <span th:if="${party.desiredStart() == null}" class="muted">—</span>
                </td>
            </tr>
            </tbody>
        </table>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 3: Link the units table to it**

In `units.html`, replace line 23:

```html
            <td th:text="${unit.name()}">—</td>
```

with:

```html
            <td><a th:href="@{/units/{id}(id=${unit.unitId()})}" th:text="${unit.name()}">—</a></td>
```

- [ ] **Step 4: Add the two new CSS rules**

Append to `apps/najem-app/src/main/resources/static/css/najem.css`. Follow the file's existing custom-property names — open it and use the same `var(--…)` tokens the neighbouring rules use rather than literal colours.

```css
.crumb {
    margin-bottom: 0.5rem;
    font-size: 0.9rem;
}

.unit__facts {
    display: flex;
    gap: 0.75rem;
    align-items: baseline;
    flex-wrap: wrap;
    margin-bottom: 1.5rem;
}
```

- [ ] **Step 5: Build, then look at the screen**

```
./gradlew :apps:najem-app:build
```

Expected: BUILD SUCCESSFUL.

Then restart the running app and open a unit. The app runs from the repo root with:

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
KCU=http://localhost:8180/realms/najem
./gradlew :apps:najem-app:bootRun --console=plain --args="\
  --spring.security.oauth2.client.registration.keycloak.client-id=najem-app \
  --spring.security.oauth2.client.registration.keycloak.client-authentication-method=none \
  --spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email \
  --spring.security.oauth2.client.provider.keycloak.issuer-uri=$KCU \
  --spring.security.oauth2.resourceserver.jwt.issuer-uri=$KCU \
  --najem.security.audience=najem-app \
  --najem.bootstrap.operator-subject=e696ddc0-cbd9-4967-b8d6-3525644cec41 \
  --najem.bank.fake.enabled=true --najem.bank.base-url=http://localhost:8081"
```

Sign in as `demo`/`demo`, go to Nieruchomości → ul. Marszałkowska 12 → click **m. 2**. Expected: the unit's name, rent, `Nieogłoszony`, `Pustostan`, and "Nikt jeszcze nie pytał o ten lokal." A blank page or a stack trace is a failure, not a detail.

- [ ] **Step 6: Commit**

```bash
git add apps/najem-app/src/main/java/pl/najem/app/web/UnitScreenController.java \
        apps/najem-app/src/main/resources/templates/unit.html \
        apps/najem-app/src/main/resources/templates/units.html \
        apps/najem-app/src/main/resources/static/css/najem.css
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Give a unit a screen of its own"
```

---

### Task 5: Recording the caller

The form, its three states, and the POST that either reuses a person or creates one.

**Files:**
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/UnitScreenController.java`
- Modify: `apps/najem-app/src/main/resources/templates/unit.html`
- Modify: `apps/najem-app/src/main/resources/static/css/najem.css` (append)
- Test: `apps/najem-app/src/test/java/pl/najem/app/web/LeadFormTest.java` (create)

**Interfaces:**
- Consumes: `ContactService.registerLead` (Task 3), `InterestService.register(UUID workspaceId, UUID contactId, UUID unitId, BigDecimal willingToPay, LocalDate desiredStart) -> UUID`, `ContactDirectory.search(UUID, String) -> List<ContactMatch>`, `ContactDirectory.find(UUID, UUID) -> Optional<ContactDetails>`.
- Produces: `POST /units/{unitId}/interests`; model attributes `term`, `asked`, `hits`, `chosen`, `chosenId`.

- [ ] **Step 1: Write the failing test for the one branch worth isolating**

The controller's real work is a two-way branch — reuse the person the manager picked, or create one — and it is worth naming so a reader does not have to infer it from an `if`. The precedent for pulling a decision out of a controller and testing it alone is `SearchGroupingTest`: grouping moved out of `search.html` after an expression that failed only when there was something to show, with every test green because none ever produced a hit.

Create `apps/najem-app/src/test/java/pl/najem/app/web/LeadFormTest.java`:

```java
package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The form's own reading of itself, with nothing booted.
 *
 * <p>Small, and worth having anyway: "did the manager pick somebody, or describe somebody" is the
 * decision the whole screen turns on, and a blank hidden field is the shape it arrives in.
 */
class LeadFormTest {

    @Test
    void ablankChosenIdMeansTheManagerIsDescribingANewPerson() {
        assertThat(UnitScreenController.chosen("")).isEmpty();
        assertThat(UnitScreenController.chosen("   ")).isEmpty();
        assertThat(UnitScreenController.chosen(null)).isEmpty();
    }

    @Test
    void anidMeansTheManagerPickedSomebodyWeAlreadyKnow() {
        var contactId = UUID.randomUUID();

        assertThat(UnitScreenController.chosen(contactId.toString())).contains(contactId);
    }

    /**
     * The field is a hidden input on a form anybody signed in can post. A value that is not a UUID
     * is a bad request, not a 500 — and not a silently created duplicate person either.
     */
    @Test
    void agarbledIdIsRefusedRatherThanTreatedAsANewPerson() {
        assertThat(UnitScreenController.chosen("not-a-uuid")).isEmpty();
    }
}
```

Note the third test asserts `isEmpty()`, i.e. garbage falls through to the create path. **That is wrong and the test is written to make you choose deliberately:** in Step 3 you will implement `chosen` to throw `IllegalArgumentException` on a malformed id, then change this assertion to `assertThatThrownBy(() -> UnitScreenController.chosen("not-a-uuid")).isInstanceOf(IllegalArgumentException.class)`. Do not leave it as `isEmpty()` — a garbled id silently creating a second person is exactly the duplicate this screen exists to avoid.

- [ ] **Step 2: Run it and watch it fail**

```
./gradlew :apps:najem-app:test --tests '*LeadFormTest*'
```

Expected: compilation failure — `cannot find symbol: method chosen`.

- [ ] **Step 3: Extend the controller**

Add the fields and constructor parameters for `ContactService contacts`, `InterestService interests` and `ContactDirectory directory`, then:

```java
    /**
     * Whether the manager picked somebody we already know.
     *
     * <p>Package-private and static so it can be tested without booting anything — the
     * {@code SearchGroupingTest} precedent. Blank is "no", and a malformed value is refused rather
     * than falling through to the create path: a garbled hidden field silently registering a second
     * person is the duplicate this screen exists to prevent.
     */
    static Optional<UUID> chosen(String contactId) {
        if (contactId == null || contactId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(contactId.strip()));
    }
```

Extend the GET mapping to carry the search and the choice:

```java
    @GetMapping("/units/{unitId}")
    public String unit(@PathVariable UUID unitId,
                       @RequestParam(name = "q", required = false) String term,
                       @RequestParam(name = "contactId", required = false) String contactId,
                       WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("unit", unit);
        model.addAttribute("interested", interested.activeForUnit(workspaceId, unitId));

        // "Type something" and "nobody matched" are different answers and must not share a message,
        // for the same reason the search screen distinguishes them: a blank term returns no rows
        // exactly as a term that matched nothing does, so the difference cannot come from the result.
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));

        var picked = chosen(contactId);
        model.addAttribute("chosenId", picked.orElse(null));
        model.addAttribute("chosen", picked.flatMap(id -> directory.find(workspaceId, id)).orElse(null));
        return "unit";
    }
```

And the write:

```java
    /**
     * Somebody phoned about this unit.
     *
     * <p>One endpoint and two paths, because from the manager's side it is one action. Which path
     * ran is the presence of {@code contactId} — the hidden field the "Wybierz" link fills in.
     *
     * <p>The person is registered before the interest, and both are separate transactions on the
     * services that own them. A failure between the two leaves a person with no interest, which a
     * manager can see and fix; the reverse would leave an interest pointing at nobody, which the
     * inner join would hide.
     */
    @PostMapping("/units/{unitId}/interests")
    public String addInterest(@PathVariable UUID unitId,
                              @RequestParam(required = false) String contactId,
                              @RequestParam(required = false) String givenName,
                              @RequestParam(required = false) String surname,
                              @RequestParam(required = false) String email,
                              @RequestParam(required = false) String phone,
                              // An unticked checkbox submits NOTHING, so this parameter is absent
                              // rather than "false" — and a primitive boolean with required=false
                              // and no default fails to bind a missing value. defaultValue is what
                              // makes "the manager did not tick it" the ordinary path.
                              @RequestParam(defaultValue = "false") boolean infoClauseServed,
                              @RequestParam(required = false) BigDecimal willingToPay,
                              @RequestParam(required = false)
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desiredStart,
                              WebWorkspace workspace) {
        UUID workspaceId = workspace.workspaceId();
        UUID person = chosen(contactId).orElseGet(() -> contacts.registerLead(workspaceId,
            new ContactDetails(givenName, surname, email, phone),
            infoClauseServed, LocalDate.now(clock)));
        interests.register(workspaceId, person, unitId, willingToPay, desiredStart);
        return "redirect:/units/" + unitId;
    }
```

Imports to add: `org.springframework.format.annotation.DateTimeFormat`, `org.springframework.web.bind.annotation.PostMapping`, `org.springframework.web.bind.annotation.RequestParam`, `pl.najem.contacts.application.ContactDetails`, `pl.najem.contacts.application.ContactDirectory`, `pl.najem.contacts.application.ContactService`, `pl.najem.contacts.application.InterestService`, `java.math.BigDecimal`, `java.util.Optional`.

`LocalDate.now(clock)` is correct **here** — the app layer is where today comes from. It would be wrong inside `modules/contacts`, which is why `registerLead` takes it as an argument.

- [ ] **Step 4: Fix the third test and run them**

Change `agarbledIdIsRefusedRatherThanTreatedAsANewPerson` to expect the throw, as Step 1 said:

```java
    @Test
    void agarbledIdIsRefusedRatherThanTreatedAsANewPerson() {
        assertThatThrownBy(() -> UnitScreenController.chosen("not-a-uuid"))
            .isInstanceOf(IllegalArgumentException.class);
    }
```

with `import static org.assertj.core.api.Assertions.assertThatThrownBy;`.

```
./gradlew :apps:najem-app:test --tests '*LeadFormTest*'
```

Expected: PASS.

- [ ] **Step 5: Add the form to the template**

Insert before `</main>` in `unit.html`:

```html
    <section class="lead">
        <h2>Dodaj zainteresowanego</h2>

        <!--/*
          A GET, so the search is bookmarkable and the screen keeps one template for all three
          states: nothing asked, hits to pick from, somebody picked. It carries no CSRF token
          because it changes nothing — only the two forms below do.
        */-->
        <form class="search" th:action="@{/units/{id}(id=${unit.unitId()})}" method="get">
            <input type="search" name="q" th:value="${term}" placeholder="Nazwisko albo imię">
            <button type="submit">Szukaj</button>
        </form>

        <p class="empty" th:if="${asked and hits.isEmpty()}">
            Nikogo takiego jeszcze nie znamy — wpisz dane poniżej.
        </p>

        <ul class="hits" th:unless="${hits.isEmpty()}">
            <li th:each="hit : ${hits}">
                <a th:href="@{/units/{id}(id=${unit.unitId()}, contactId=${hit.contactId()})}"
                   th:text="${hit.givenName()} + ' ' + ${hit.surname()} + ' — ' + ${hit.email()}">—</a>
            </li>
        </ul>

        <form th:action="@{/units/{id}/interests(id=${unit.unitId()})}" method="post">
            <!--/*
              Two shapes of the same form. With somebody picked, the person is settled and only the
              two facts about the call remain; without, the manager describes them. Rendering both
              at once would let a form both name a person and describe a different one.
            */-->
            <div th:if="${chosen != null}" class="lead__chosen">
                <input type="hidden" name="contactId" th:value="${chosenId}">
                <p>
                    <strong th:text="${chosen.givenName()} + ' ' + ${chosen.surname()}">—</strong>
                    <span class="muted" th:text="${chosen.email()}">—</span>
                    <a th:href="@{/units/{id}(id=${unit.unitId()})}">zmień</a>
                </p>
            </div>

            <div th:if="${chosen == null}" class="lead__new">
                <input type="text" name="givenName" placeholder="Imię" required>
                <input type="text" name="surname" placeholder="Nazwisko" required>
                <input type="email" name="email" placeholder="E-mail">
                <input type="tel" name="phone" placeholder="Telefon">
                <!--/*
                  The one fact on this form that is genuinely the manager's to assert. Defaulting it
                  to today would have the application claim the clause was read out when nobody said
                  so — see ContactService.registerLead.
                */-->
                <label>
                    <input type="checkbox" name="infoClauseServed" value="true">
                    Przekazano klauzulę informacyjną
                </label>
            </div>

            <input type="text" name="willingToPay" placeholder="Gotów płacić" inputmode="decimal">
            <input type="date" name="desiredStart">
            <button type="submit">Dodaj</button>
        </form>
    </section>
```

- [ ] **Step 6: Add the CSS**

Append to `najem.css`, again reusing the file's existing `var(--…)` tokens:

```css
.lead form {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    flex-wrap: wrap;
    margin-top: 0.75rem;
}

.lead__chosen p,
.lead__new {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    flex-wrap: wrap;
    margin: 0;
}
```

- [ ] **Step 7: Drive it in the browser**

Build, restart the app (command in Task 4 Step 5), and on **m. 2**:

1. Type `nowak` in "Szukaj osoby" → Piotr Nowak appears (the seed created him).
2. Click him → he shows as chosen, the name fields are gone.
3. Fill 2900 and a date, click Dodaj → he appears in "Zainteresowani".
4. Reload with no `contactId`, fill in a brand new person, tick the clause, click Dodaj → they appear too.
5. Confirm no second Piotr Nowak was created:

```
docker exec najem-postgres-1 psql -U najem -d najem \
  -c "select given_name, surname, lawful_basis, info_clause_served_at from contacts_person order by surname;"
```

Expected: one Nowak row, and the new person with `legitimate-interest`.

- [ ] **Step 8: Commit**

```bash
git add apps/najem-app/src/main/java/pl/najem/app/web/UnitScreenController.java \
        apps/najem-app/src/test/java/pl/najem/app/web/LeadFormTest.java \
        apps/najem-app/src/main/resources/templates/unit.html \
        apps/najem-app/src/main/resources/static/css/najem.css
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Search for the caller before offering to invent them"
```

---

### Task 6: Withdrawing an interest

**Files:**
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/UnitScreenController.java`
- Modify: `apps/najem-app/src/main/resources/templates/unit.html`

**Interfaces:**
- Consumes: `InterestService.withdraw(UUID workspaceId, UUID interestId, LocalDate withdrawnOn)`.
- Produces: `POST /units/{unitId}/interests/{interestId}/withdraw`.

- [ ] **Step 1: Add the mapping**

```java
    /**
     * POST rather than DELETE because an HTML form cannot issue one, and the same shape the bank
     * screen's two buttons already use.
     *
     * <p>The service's own lookup is the workspace gate — it names the workspace, so a foreign or
     * unknown interest finds nothing and the command is refused before anything is appended. This
     * passes the workspace on rather than checking it here.
     */
    @PostMapping("/units/{unitId}/interests/{interestId}/withdraw")
    public String withdraw(@PathVariable UUID unitId, @PathVariable UUID interestId,
                           WebWorkspace workspace) {
        interests.withdraw(workspace.workspaceId(), interestId, LocalDate.now(clock));
        return "redirect:/units/" + unitId;
    }
```

- [ ] **Step 2: Add the column**

In `unit.html`, add a header cell to the interested table:

```html
                <th></th>
```

after `<th>Od kiedy</th>`, and a cell at the end of each row:

```html
                <td>
                    <form th:action="@{/units/{u}/interests/{i}/withdraw(u=${unit.unitId()}, i=${party.interestId()})}"
                          method="post">
                        <button type="submit">Wycofaj</button>
                    </form>
                </td>
```

The CSRF token arrives from `th:action` without a hidden field, as it does on the bank screen.

- [ ] **Step 3: Build and drive it**

```
./gradlew :apps:najem-app:build
```

Restart, open **m. 2**, click **Wycofaj** on one row. Expected: the row goes, the others stay, and the count in the heading drops by one.

- [ ] **Step 4: Commit**

```bash
git add apps/najem-app/src/main/java/pl/najem/app/web/UnitScreenController.java \
        apps/najem-app/src/main/resources/templates/unit.html
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Let a mistyped lead be taken off the list"
```

---

### Task 7: The flow, end to end

The screen has been driven by hand; this is what keeps it working. Container tier, because the join, the redirect and the CSRF filter are all things only a running stack decides.

**Files:**
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/UnitScreenTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Write the test**

The setup follows `WebCsrfTest`: same `@SpringBootTest` properties, same `@ServiceConnection` container, same `csrf()` post-processor, `permit-all` so no Keycloak is needed. Every request then acts as the configured operator.

The unit must exist in Reporting's projection, which is fed asynchronously. `ProjectionRunner` is a bean here, so **autowire it and call `runOnce()`** after the PM writes. **No `Thread.sleep`** — a timing-dependent test passes on a fast machine and fails on CI for reasons nobody can reproduce, and `@Scheduled` polling would make it flaky in exactly that way.

```java
package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.UnitInterestQuery;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scenario the screen exists for, through the stack a manager actually uses.
 *
 * <p>Container tier, because the three things this covers are all things only a running stack
 * decides: the join between the interest and the person, the redirect, and the CSRF filter.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + UnitScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class UnitScreenTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000010";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;
    @Autowired ContactDirectory directory;
    @Autowired UnitInterestQuery interested;
    @Autowired JdbcTemplate jdbc;

    /** Static: JUnit builds a new instance per method, so an instance guard would never be false. */
    static UUID agency;
    static UUID unitId;

    @BeforeEach
    void aUnitToRingAbout() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency == null) {
            agency = workspaces.create("Agencja Zainteresowanych", operator, LocalDate.now());
            var property = portfolio.createProperty(agency, "ul. Testowa 1, 00-001 Warszawa",
                List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
            unitId = portfolio.addUnit(agency, property, "m. 2", new BigDecimal("2850"));
        }
        // Drained explicitly rather than waited for. The scheduled poll would make this pass or
        // fail on timing, which is the one kind of red nobody can reproduce.
        projections.runOnce();
    }

    private void ring(String... params) throws Exception {
        var request = post("/units/" + unitId + "/interests").with(csrf());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        mvc.perform(request)
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/units/" + unitId));
    }

    @Test
    void aleadPhonesAboutAUnitAndAppearsOnItsScreen() throws Exception {
        ring("givenName", "Piotr", "surname", "Nowak",
            "email", "p.nowak@example.com", "phone", "+48500000000",
            "infoClauseServed", "true",
            "willingToPay", "2900.00", "desiredStart", "2026-09-01");

        mvc.perform(get("/units/" + unitId))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Piotr Nowak")))
            // The stated format, not the server locale's — see units.html.
            .andExpect(content().string(containsString("2 900,00")));
    }

    /** The basis is the module's decision, and this is where it becomes visible in a row. */
    @Test
    void aleadIsStoredUnderLegitimateInterestWithTheClauseTheManagerConfirmed() throws Exception {
        ring("givenName", "Marta", "surname", "Wisniewska",
            "email", "m.w@example.com", "infoClauseServed", "true");

        var row = jdbc.queryForMap(
            "select lawful_basis, info_clause_served_at from contacts_person where email = ?",
            "m.w@example.com");

        assertThat(row.get("lawful_basis")).isEqualTo("legitimate-interest");
        assertThat(row.get("info_clause_served_at")).isNotNull();
    }

    /** Unticked means unticked. Recording today anyway would be the application asserting it. */
    @Test
    void anunconfirmedClauseIsStoredAsAbsent() throws Exception {
        ring("givenName", "Jan", "surname", "Bezklauzuli", "email", "j.b@example.com");

        assertThat(jdbc.queryForObject(
            "select info_clause_served_at from contacts_person where email = ?",
            LocalDate.class, "j.b@example.com")).isNull();
    }

    /**
     * The point of searching before creating. A second interest under the picked contact must not
     * produce a second person: erasure deletes one row, and would leave the other behind.
     */
    @Test
    void pickingSomebodyWeKnowDoesNotCreateThemAgain() throws Exception {
        ring("givenName", "Tomasz", "surname", "Zielinski", "email", "t.z@example.com");
        var known = directory.search(agency, "Zielinski").getFirst().contactId();

        ring("contactId", known.toString(), "willingToPay", "3100.00");

        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where workspace_id = ? and email = ?",
            Integer.class, agency, "t.z@example.com")).isEqualTo(1);
        assertThat(interested.activeForUnit(agency, unitId))
            .filteredOn(party -> party.contactId().equals(known))
            .hasSize(2);
    }

    @Test
    void withdrawingTakesThemOffTheList() throws Exception {
        ring("givenName", "Krzysztof", "surname", "Wycofany", "email", "k.w@example.com");
        var interestId = interested.activeForUnit(agency, unitId).stream()
            .filter(party -> "Wycofany".equals(party.surname()))
            .findFirst().orElseThrow().interestId();

        mvc.perform(post("/units/" + unitId + "/interests/" + interestId + "/withdraw").with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/units/" + unitId));

        mvc.perform(get("/units/" + unitId))
            .andExpect(content().string(not(containsString("Krzysztof Wycofany"))));
    }

    /**
     * A unit belonging to another agency must 404 rather than render an empty screen. An empty
     * screen says "nobody is interested", which is a different claim from "not yours" — and the
     * interest list alone would render empty quite happily.
     */
    @Test
    void aunitInAnotherAgencyIsNotFound() throws Exception {
        UUID stranger = users.register(UUID.randomUUID(), LocalDate.now());
        UUID theirs = workspaces.create("Cudza agencja", stranger, LocalDate.now());
        var theirProperty = portfolio.createProperty(theirs, "ul. Cudza 9, Sopot",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100"))));
        var theirUnit = portfolio.addUnit(theirs, theirProperty, "m. 2", new BigDecimal("2000"));
        projections.runOnce();

        mvc.perform(get("/units/" + theirUnit)).andExpect(status().isNotFound());
    }

    /** Without this, the refusal above could be CSRF, or a route that does not exist. */
    @Test
    void thewriteIsRefusedWithoutACsrfToken() throws Exception {
        mvc.perform(post("/units/" + unitId + "/interests")
                .param("givenName", "Nikt").param("surname", "Nigdy"))
            .andExpect(status().isForbidden());
    }
}
```

Two things to check while writing it rather than after:

- **`@ServiceConnection` on the container** points Spring's datasource at it, so Flyway runs every module's migrations on boot. No manual `Flyway.configure()` here, unlike the module-level tests.
- **`aunitInAnotherAgencyIsNotFound` creates a second workspace under a different operator.** Under `permit-all` every request acts as the configured operator, so that second agency is one the caller does not belong to — which is what makes the 404 meaningful. If `WorkspaceService.create` refuses for a reason this fixture cannot satisfy, read `WebWorkspaceChoiceTest`, which already creates two agencies, and follow it.

- [ ] **Step 2: Run it**

```
./gradlew :apps:najem-app:test -PintegrationTests --tests '*UnitScreenTest*'
```

Expected: PASS, all four.

- [ ] **Step 3: Mutate one and confirm it can fail**

A test whose job is to be red one day proves nothing while it is green (rule 21). Break `chosen` so it returns `Optional.empty()` for a well-formed id, run `pickingSomebodyWeKnowDoesNotCreateThemAgain`, confirm it goes red, and revert. Note in your report that you did it.

- [ ] **Step 4: Run both tiers whole**

```
./gradlew build
./gradlew build -PintegrationTests
```

Expected: both BUILD SUCCESSFUL. `:e2e:test` is already red for an unrelated reason — both applications map `GET /` on one classpath, per RUNNING.md. Confirm that is still the only failure there and do not try to fix it.

- [ ] **Step 5: Commit**

```bash
git add apps/najem-app/src/test/java/pl/najem/app/web/UnitScreenTest.java
git -c user.name="jacekku" -c user.email="jacekwyroba97@gmail.com" \
  commit -m "Hold the lead scenario down end to end"
```

---

## Not in scope

Named so nobody reads this plan as having delivered them:

- a `/contacts` section, or any person-detail screen
- people in the masthead search. `search.html` carries a comment saying "a unit on its own is not somewhere a person can go", which this work makes untrue — leave it, and note it in the final report as a follow-up rather than widening scope here
- lead stages, qualification, or turning an interest into a tenancy. None of it is modelled: there is no event and no field, so it is domain work rather than a view
- the two defects found while seeding on 2026-08-07 — `tools/seed-demo.sh` mangling non-ASCII through `curl -d` argv on Git Bash, and RUNNING.md's signed-in seeding path being unable to bootstrap a fresh database because `PlatformOperator` only registers its user through the unauthenticated path. Both are real and neither belongs in this branch.
