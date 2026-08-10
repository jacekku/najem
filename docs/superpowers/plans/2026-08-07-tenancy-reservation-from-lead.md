# Tenancy Reservation From A Lead — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From a unit's *Zainteresowani* list, a manager confirms the lead who said yes and creates the tenancy reservation for them.

**Architecture:** Contacts gains the lead→tenancy link (`InterestConverted`) and the RODO consequence of becoming a tenant (`LawfulBasisChanged`), plus a state machine on the interest so a converted lead cannot be silently withdrawn. PM gains one read (`isReserved`) answered from the event stream. The app gains a two-phase reserve screen and a cancel button, driving three modules' services from one controller.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Gradle, Thymeleaf, Postgres + Flyway, JUnit 5, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-08-07-tenancy-reservation-from-lead-design.md`

## Global Constraints

- **`JAVA_HOME` must be exported before every gradle command.** The default on this machine is JDK 19 and the build needs 21:
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"`
- **Do not run `./gradlew bootRun` or any `:e2e:` task.** An application instance holds port 8080 for the whole of this work. The inner loop is `./gradlew build` (~45s, integration tests excluded by default).
- **Integration tests are `@Tag("integration")`** and run with `./gradlew build -PintegrationTests` (~8min). Rule 16: when the two tiers disagree, the database is right.
- **Architecture rules bind every task** (`.claude/rules/architecture.md`, `.claude/rules/refactoring.md`). Specifically: `application` must never import `adapter`; `domain` imports nothing outside the JDK; every driven port has a Postgres adapter **and** an in-memory double; a fake models the mechanism, not the outcome (rule 14).
- **All user-facing copy is Polish.** Existing templates are the reference for tone.
- **Never `th:text` a `BigDecimal` bare.** Always `${#numbers.formatDecimal(x, 1, 'WHITESPACE', 2, 'COMMA')}` — Thymeleaf's default takes the separator from the server locale.
- **No CDN.** Anything fetched is served locally.
- **CSRF arrives free from Thymeleaf's `th:action`** on POST forms. A GET form carries none and must not change anything.
- **An unticked checkbox submits nothing.** A `boolean` request parameter needs `defaultValue = "false"`, not `required = false`, which fails to bind a missing value.
- **The contacts module reads no clock** — `WallClockTest` asserts it. Dates are parameters.
- **Commits:** do not sign as Claude and do not mention an LLM in the message.

---

### Task 1: The interest state machine

`InterestRepository.contactOf` deliberately ignores status. Task 2 adds a `converted` status, and without this task withdrawing a converted interest would flip it to `withdrawn` and drop the tenancy link — losing the only record that the lead was won. This task installs the guard **before** the status that needs it exists.

**Files:**
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestRepository.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/adapter/persistence/PostgresInterestRepository.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestNotActiveException.java`
- Modify: `modules/contacts/src/test/java/pl/najem/contacts/application/InMemoryInterests.java`
- Modify: `modules/contacts/src/test/java/pl/najem/contacts/application/InterestServiceTest.java`

**Interfaces:**
- Produces: `InterestRepository.find(UUID workspaceId, UUID interestId) → Optional<Interest>` replacing `contactOf`. `InterestNotActiveException(UUID interestId, String status)`.

- [ ] **Step 1: Write the failing tests**

In `InterestServiceTest`:

```java
@Test
void withdrawingTwiceIsRefused() {
    UUID contact = contacts.register(new NewContact(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), "legitimate-interest", null, null));
    UUID interest = interests.register(WORKSPACE, contact, UNIT, new BigDecimal("2900"), null);

    interests.withdraw(WORKSPACE, interest, LocalDate.of(2026, 8, 7));

    assertThatThrownBy(() -> interests.withdraw(WORKSPACE, interest, LocalDate.of(2026, 8, 8)))
        .isInstanceOf(InterestNotActiveException.class);
}

@Test
void aForeignInterestIsNotFound() {
    UUID contact = contacts.register(new NewContact(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), "legitimate-interest", null, null));
    UUID interest = interests.register(WORKSPACE, contact, UNIT, null, null);

    assertThatThrownBy(() -> interests.withdraw(OTHER_WORKSPACE, interest, LocalDate.of(2026, 8, 7)))
        .isInstanceOf(NoSuchInterestException.class);
}
```

Reuse whatever fixture fields `InterestServiceTest` already declares for the workspace, unit and services — read the file first and match its existing setup rather than introducing new names. If it has no second-workspace constant, add one.

- [ ] **Step 2: Run and watch them fail**

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew :modules:contacts:test --tests '*InterestServiceTest*'
```

Expected: `withdrawingTwiceIsRefused` fails — the second withdraw currently succeeds. `aForeignInterestIsNotFound` should already pass; it is a regression guard for the port change.

- [ ] **Step 3: Replace `contactOf` with `find` on the port**

In `InterestRepository`, delete `contactOf` and add, carrying its javadoc across because every word of it still applies:

```java
    /**
     * The interest, if this workspace owns it.
     *
     * <p>This lookup <em>is</em> the workspace gate for every command on an interest: it names the
     * workspace, so a foreign or unknown interest finds nothing and the command is refused before
     * anything is appended. An implementation that answered without filtering on the workspace would
     * open the module's only unguarded write, so the in-memory double filters too (rule 14).
     *
     * <p>It returns the whole {@link Interest} rather than the contact id because status is now part
     * of the answer: {@code withdraw} and {@code convert} both refuse anything that is not active,
     * and two commands each remembering to ask separately is the procedural invariant rule 9 says to
     * replace with a structural one.
     *
     * <p>Empty rather than an exception, deliberately. The Postgres form used {@code queryForObject}
     * once, whose {@code EmptyResultDataAccessException} reaches the edge as a <b>500</b> — so an
     * ordinary "not yours" was reported as the server having broken, and an alert on 5xx fired for
     * routine traffic.
     */
    Optional<Interest> find(UUID workspaceId, UUID interestId);
```

- [ ] **Step 4: Implement it in Postgres**

```java
    @Override
    public Optional<Interest> find(UUID workspaceId, UUID interestId) {
        return jdbc.query("""
            select interest_id, contact_id, unit_id, willing_to_pay, desired_start, status
            from contacts_interest where workspace_id = ? and interest_id = ?
            """,
            (rs, i) -> new Interest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class), rs.getString(6)),
            workspaceId, interestId)
            .stream().findFirst();
    }
```

- [ ] **Step 5: Add the exception**

```java
package pl.najem.contacts.application;

import java.util.UUID;

/**
 * The interest exists and belongs to this workspace, but has already been withdrawn or converted.
 *
 * <p>Distinct from {@link NoSuchInterestException} on purpose: that one merges unknown with foreign
 * and must stay a 404 disclosing nothing. This one is a state the caller may legitimately know
 * about, because they own it.
 */
public class InterestNotActiveException extends RuntimeException {

    public InterestNotActiveException(UUID interestId, String status) {
        super("Interest " + interestId + " is " + status);
    }
}
```

- [ ] **Step 6: Guard the command in `InterestService`**

Replace `withdraw`'s lookup, and add the shared gate:

```java
    public void withdraw(UUID workspaceId, UUID interestId, LocalDate withdrawnOn) {
        var interest = requireActive(workspaceId, interestId);
        UUID contactId = interest.contactId();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestWithdrawn(workspaceId, interestId, contactId, withdrawnOn)), List.of());
        interests.withdraw(workspaceId, interestId);
    }

    /**
     * One lookup answering both questions every command on an interest has to ask: is it yours, and
     * is it still open. Shared so that a future command cannot get the second one wrong by omission.
     */
    private Interest requireActive(UUID workspaceId, UUID interestId) {
        var interest = interests.find(workspaceId, interestId)
            .orElseThrow(() -> new NoSuchInterestException(interestId));
        if (!"active".equals(interest.status())) {
            throw new InterestNotActiveException(interestId, interest.status());
        }
        return interest;
    }
```

- [ ] **Step 7: Update the in-memory double**

`InMemoryInterests` must store the status and read it back, exactly as the SQL does — it must not derive "active" from whether `withdraw` was called on some other field (rule 14). Read the file, then replace `contactOf` with `find` returning the stored `Interest` including its current status, filtered on `workspaceId`.

- [ ] **Step 8: Run the tests**

```
./gradlew :modules:contacts:test --tests '*Interest*'
```

Expected: PASS.

- [ ] **Step 9: Mutate the tripwire before believing it (rule 21)**

Temporarily drop the `workspaceId` filter from `InMemoryInterests.find`. `aForeignInterestIsNotFound` must go red. Revert. Then temporarily make `requireActive` skip the status check; `withdrawingTwiceIsRefused` must go red. Revert. Record both in the report.

- [ ] **Step 10: Run the whole build and commit**

```
./gradlew build
git add -A && git commit -m "Ask an interest whether it is still open before commanding it"
```

---

### Task 2: `InterestConverted`

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/InterestConverted.java`
- Create: `modules/contacts/src/main/resources/db/contacts/V43__contacts_conversion.sql`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestRepository.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/adapter/persistence/PostgresInterestRepository.java`
- Modify: `modules/contacts/src/test/java/pl/najem/contacts/application/InMemoryInterests.java`
- Modify: `modules/contacts/src/test/java/pl/najem/contacts/application/InterestServiceTest.java`
- Modify: whichever test pins contacts wire names — search for `ContactRegistered` inside `modules/contacts/src/test` and add to the existing one; create `ContactsWireNamesTest` in `pl.najem.contacts` only if none exists.

**Interfaces:**
- Consumes: `InterestRepository.find` and `InterestService.requireActive` from Task 1.
- Produces: `InterestService.convert(UUID workspaceId, UUID interestId, UUID tenancyId, LocalDate convertedOn)` returning `void`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void convertingRecordsTheTenancyAndClosesTheInterest() {
    UUID contact = contacts.register(new NewContact(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), "legitimate-interest", null, null));
    UUID interest = interests.register(WORKSPACE, contact, UNIT, new BigDecimal("2900"), null);
    UUID tenancy = UUID.randomUUID();

    interests.convert(WORKSPACE, interest, tenancy, LocalDate.of(2026, 8, 7));

    assertThat(interests.forUnit(WORKSPACE, UNIT)).isEmpty();
    assertThat(interests.eventsFor(contact))
        .anySatisfy(e -> assertThat(e).isInstanceOf(InterestConverted.class));
}

@Test
void convertingTwiceIsRefused() {
    UUID contact = contacts.register(new NewContact(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), "legitimate-interest", null, null));
    UUID interest = interests.register(WORKSPACE, contact, UNIT, null, null);
    interests.convert(WORKSPACE, interest, UUID.randomUUID(), LocalDate.of(2026, 8, 7));

    assertThatThrownBy(() -> interests.convert(WORKSPACE, interest, UUID.randomUUID(), LocalDate.of(2026, 8, 8)))
        .isInstanceOf(InterestNotActiveException.class);
}

@Test
void aConvertedInterestCannotBeWithdrawn() {
    UUID contact = contacts.register(new NewContact(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), "legitimate-interest", null, null));
    UUID interest = interests.register(WORKSPACE, contact, UNIT, null, null);
    interests.convert(WORKSPACE, interest, UUID.randomUUID(), LocalDate.of(2026, 8, 7));

    assertThatThrownBy(() -> interests.withdraw(WORKSPACE, interest, LocalDate.of(2026, 8, 8)))
        .isInstanceOf(InterestNotActiveException.class);
}
```

- [ ] **Step 2: Run and watch them fail**

```
./gradlew :modules:contacts:test --tests '*InterestServiceTest*'
```

Expected: compilation failure — `convert` does not exist.

- [ ] **Step 3: The event**

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The lead signed. Carries {@code tenancyId} because the link from a lead to what they became is the
 * fact worth keeping, and it exists nowhere else — PM's stream names contact ids but not the
 * interest the manager actually chose.
 */
public record InterestConverted(UUID workspaceId, UUID interestId, UUID contactId, UUID unitId,
                                UUID tenancyId, LocalDate convertedOn) {
}
```

Register it in `ContactsEventTypes.register`, beside `InterestWithdrawn`.

- [ ] **Step 4: The migration**

`V43__contacts_conversion.sql`:

```sql
-- A lead who signed. status was already 'active' or 'withdrawn'; 'converted' is the third answer,
-- and recording a win as a withdrawal would make any later question about lead outcomes wrong in a
-- way nothing could untangle.
alter table contacts_interest add column converted_to_tenancy_id uuid;
```

- [ ] **Step 5: The port and its adapter**

Add to `InterestRepository`:

```java
    /** Closes the interest and records what it became. Scoped to the workspace, like every write here. */
    void convert(UUID workspaceId, UUID interestId, UUID tenancyId);
```

In `PostgresInterestRepository`:

```java
    @Override
    public void convert(UUID workspaceId, UUID interestId, UUID tenancyId) {
        jdbc.update("""
            update contacts_interest set status = 'converted', converted_to_tenancy_id = ?
            where workspace_id = ? and interest_id = ?
            """, tenancyId, workspaceId, interestId);
    }
```

- [ ] **Step 6: The service method**

```java
    /**
     * The lead said yes and the agreement was signed.
     *
     * <p>Called after the reservation exists, never before: converting first would mark a lead as won
     * for a tenancy that does not exist, and nothing would show it.
     */
    public void convert(UUID workspaceId, UUID interestId, UUID tenancyId, LocalDate convertedOn) {
        var interest = requireActive(workspaceId, interestId);
        UUID contactId = interest.contactId();
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestConverted(workspaceId, interestId, contactId, interest.unitId(),
                tenancyId, convertedOn)), List.of());
        interests.convert(workspaceId, interestId, tenancyId);
    }
```

- [ ] **Step 7: The in-memory double**

`InMemoryInterests.convert` stores the tenancy id and sets the status to `converted` on the stored `Interest`, so `find` reads it back — the mechanism, not the outcome.

- [ ] **Step 8: Pin the wire name (rule 12)**

Renaming a constant should stay free; renaming its wire name is a migration. Add `InterestConverted` to whichever test already asserts contacts' registered wire names, in the same style that test uses.

- [ ] **Step 9: Run and commit**

```
./gradlew build
git add -A && git commit -m "Record what a lead became, not merely that they stopped asking"
```

---

### Task 3: Lawful basis, and registering a contract party

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/LawfulBasisChanged.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactRepository.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/adapter/persistence/PostgresContactRepository.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Modify: `modules/contacts/src/test/java/pl/najem/contacts/application/InMemoryContacts.java`
- Modify: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactServiceTest.java`

**Interfaces:**
- Produces: `ContactService.registerParty(UUID workspaceId, ContactDetails details, boolean infoClauseServed, LocalDate today) → UUID`; `ContactService.becameContractParty(UUID workspaceId, UUID contactId, LocalDate today) → void`; `ContactRepository.lawfulBasisOf(UUID, UUID) → Optional<String>` and `updateLawfulBasis(UUID, UUID, String)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aLeadWhoSignsBecomesAContractParty() {
    UUID lead = contacts.registerLead(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), true, LocalDate.of(2026, 8, 1));

    contacts.becameContractParty(WORKSPACE, lead, LocalDate.of(2026, 8, 7));

    assertThat(repository.lawfulBasisOf(WORKSPACE, lead)).contains("contract");
}

@Test
void aContractPartyWhoIsAlreadyOneRecordsNothing() {
    UUID guarantor = contacts.registerParty(WORKSPACE,
        new ContactDetails("Anna", "Zielińska", "a@example.com", "+48"), true, LocalDate.of(2026, 8, 7));
    int before = events.eventsFor(guarantor).size();

    contacts.becameContractParty(WORKSPACE, guarantor, LocalDate.of(2026, 8, 7));

    assertThat(events.eventsFor(guarantor)).hasSize(before);
}

@Test
void aForeignContactCannotBeMadeAContractParty() {
    UUID lead = contacts.registerLead(WORKSPACE,
        new ContactDetails("Piotr", "Nowak", "p@example.com", "+48"), false, LocalDate.of(2026, 8, 1));

    assertThatThrownBy(() -> contacts.becameContractParty(OTHER_WORKSPACE, lead, LocalDate.of(2026, 8, 7)))
        .isInstanceOf(NoSuchContactException.class);
}
```

Match `ContactServiceTest`'s existing fixture names for the service, repository and event store — read it first. If it has no accessor for the in-memory store's events, use `InMemoryEventStore`'s existing API rather than adding one.

- [ ] **Step 2: Run and watch them fail**

```
./gradlew :modules:contacts:test --tests '*ContactServiceTest*'
```

Expected: compilation failure.

- [ ] **Step 3: The event**

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Why the agency now holds this person's data. Retention rules key on the basis, so a signed tenant
 * left on {@code legitimate-interest} makes the RODO record say something untrue.
 */
public record LawfulBasisChanged(UUID workspaceId, UUID contactId, String lawfulBasis,
                                 LocalDate changedOn) {
}
```

Register it in `ContactsEventTypes.register`, and pin its wire name in the same test Task 2 touched.

- [ ] **Step 4: The port and adapter**

In `ContactRepository`:

```java
    /**
     * Why this workspace holds the person's data. Empty means the workspace does not know them —
     * the same answer {@link #find} gives, and the reason a caller can gate on it rather than
     * issuing a scoped update that matches nothing and fails silently.
     */
    Optional<String> lawfulBasisOf(UUID workspaceId, UUID contactId);

    void updateLawfulBasis(UUID workspaceId, UUID contactId, String lawfulBasis);
```

In `PostgresContactRepository` — match the class's existing column names and style:

```java
    @Override
    public Optional<String> lawfulBasisOf(UUID workspaceId, UUID contactId) {
        return jdbc.queryForList(
            "select lawful_basis from contacts_person where workspace_id = ? and contact_id = ?",
            String.class, workspaceId, contactId).stream().findFirst();
    }

    @Override
    public void updateLawfulBasis(UUID workspaceId, UUID contactId, String lawfulBasis) {
        jdbc.update("update contacts_person set lawful_basis = ? where workspace_id = ? and contact_id = ?",
            lawfulBasis, workspaceId, contactId);
    }
```

- [ ] **Step 5: The service methods**

```java
    /** The basis a tenant or guarantor is held under. */
    private static final String CONTRACT = "contract";

    /**
     * A guarantor, or a co-tenant the agency did not already know.
     *
     * <p>{@code contract} rather than {@code legitimate-interest}: nobody phoned about a unit, and the
     * only reason the agency holds these details is the agreement being signed. Kept separate from
     * {@link #registerLead} for the reason that method's javadoc gives — the two bases are not
     * interchangeable and merging them would decide the retention question by accident.
     */
    public UUID registerParty(UUID workspaceId, ContactDetails details,
                              boolean infoClauseServed, LocalDate today) {
        return register(new NewContact(workspaceId, details, CONTRACT,
            infoClauseServed ? today : null, null));
    }

    /**
     * A lead who signed. Idempotent by reading first: appending a change from {@code contract} to
     * {@code contract} would put a decision nobody made into a stream that exists to prove what was
     * decided, and a guarantor registered moments earlier is already there.
     *
     * <p>{@code retainUntil} is still not set. How long an agency keeps a former tenant is a
     * retention policy nobody has decided, and this is not where it gets invented.
     */
    public void becameContractParty(UUID workspaceId, UUID contactId, LocalDate today) {
        String basis = contacts.lawfulBasisOf(workspaceId, contactId)
            .orElseThrow(() -> new NoSuchContactException(contactId));
        if (CONTRACT.equals(basis)) {
            return;
        }
        var stream = store.load(contactId, "Contact");
        store.append(contactId, "Contact", stream.version(),
            List.of(new LawfulBasisChanged(workspaceId, contactId, CONTRACT, today)), List.of());
        contacts.updateLawfulBasis(workspaceId, contactId, CONTRACT);
    }
```

- [ ] **Step 6: The in-memory double**

`InMemoryContacts` stores `NewContact`, so `lawfulBasisOf` reads the stored basis filtered on workspace, and `updateLawfulBasis` replaces it. It must not return a constant.

- [ ] **Step 7: Run, mutate, commit**

```
./gradlew build
```

Mutate: make `becameContractParty` skip the `CONTRACT.equals` check — `aContractPartyWhoIsAlreadyOneRecordsNothing` must go red. Revert. Record it.

```
git add -A && git commit -m "Say why the agency holds a tenant's details once they are one"
```

---

### Task 4: Ask the stream whether a tenancy is still reserved

**Files:**
- Modify: `modules/propertymanagement/src/main/java/pl/najem/pm/domain/Tenancy.java`
- Modify: `modules/propertymanagement/src/main/java/pl/najem/pm/application/TenancyService.java`
- Modify: the existing PM test that already covers reserve/cancel — find it with `grep -rl "cancelReservation" modules/propertymanagement/src/test`

**Interfaces:**
- Produces: `TenancyService.isReserved(UUID workspaceId, UUID tenancyId) → boolean`; `Tenancy.isReserved() → boolean`.

- [ ] **Step 1: Write the failing test**

In the PM test that already reserves and cancels, matching its fixture style:

```java
@Test
void aReservedTenancyIsReservedUntilItIsCancelled() {
    UUID tenancy = tenancies.reserve(WORKSPACE, aReservation()).tenancyId();
    assertThat(tenancies.isReserved(WORKSPACE, tenancy)).isTrue();

    tenancies.cancelReservation(WORKSPACE, tenancy, "pomyłka");
    assertThat(tenancies.isReserved(WORKSPACE, tenancy)).isFalse();
}

@Test
void anActivatedTenancyIsNoLongerReserved() {
    UUID tenancy = tenancies.reserve(WORKSPACE, aReservation()).tenancyId();
    tenancies.activate(WORKSPACE, tenancy, LocalDate.of(2026, 9, 1));

    assertThat(tenancies.isReserved(WORKSPACE, tenancy)).isFalse();
}
```

`aReservation()` is whatever helper that test file already uses to build a `ReserveTenancy`; reuse it rather than inventing one.

- [ ] **Step 2: Run and watch it fail**

```
./gradlew :modules:propertymanagement:test
```

Expected: compilation failure.

- [ ] **Step 3: The accessor on the aggregate**

In `Tenancy`, beside the existing state checks:

```java
    /** Still only a reservation: nothing has started and it can still be cancelled. */
    public boolean isReserved() {
        return state == State.RESERVED;
    }
```

- [ ] **Step 4: The service read**

```java
    /**
     * Whether the cancel button should be offered.
     *
     * <p>Answered from the stream and not from {@code pm_tenancy}, deliberately. PM is event-sourced:
     * the stream is the record and the table is a derived copy written after the append, so a screen
     * deciding from the copy would disagree with {@link #cancelReservation} — which decides from the
     * aggregate — on a day nobody is watching. That is the one-question-two-answers failure that
     * retired {@code WorkspaceGuard}, and {@link TenancyProjection} stays write-only because of it.
     */
    public boolean isReserved(UUID workspaceId, UUID tenancyId) {
        var tenancy = Tenancy.from(store.load(tenancyId, "Tenancy").events());
        tenancy.requireOwnedBy(workspaceId);
        return tenancy.isReserved();
    }
```

- [ ] **Step 5: Run and commit**

```
./gradlew build
git add -A && git commit -m "Ask the tenancy stream whether it is still only a reservation"
```

---

### Task 5: The two pure helpers

Both are extracted so they can be tested without booting anything — the `SearchGroupingTest` precedent, where grouping moved out of a template after an expression that failed only when there was something to show, and every test passed because no test ever produced a hit.

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/PaymentReferences.java`
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/PartiesDraft.java`
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/PaymentReferencesTest.java`
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/PartiesDraftTest.java`

**Interfaces:**
- Consumes: `InvalidContactIdException` and `UnitScreenController.chosen`, which already exist.
- Produces: `PaymentReferences.suggest(String unitName, LocalDate startDate) → String`;
  `PartiesDraft.of(List<String> tenants, List<String> guarantors) → PartiesDraft` with
  `List<UUID> tenants()` and `List<UUID> guarantors()`.

- [ ] **Step 1: Write the failing tests**

`PaymentReferencesTest`:

```java
@Test
void aReferenceNamesTheUnitAndTheMonthItStarts() {
    assertThat(PaymentReferences.suggest("m. 2", LocalDate.of(2026, 9, 1)))
        .isEqualTo("NAJEM/M2/2026-09");
}

@Test
void punctuationAndDiacriticsAreCollapsed() {
    assertThat(PaymentReferences.suggest("lokal A/1", LocalDate.of(2026, 12, 31)))
        .isEqualTo("NAJEM/LOKALA1/2026-12");
}

/**
 * Named rather than fixed. Two units with the same name in different properties collide, the
 * manager can see and change the value, and no port exists to ask accounting whether a reference
 * is taken. The test pins the collision so nobody discovers it in production instead.
 */
@Test
void twoUnitsNamedAlikeInDifferentPropertiesCollide() {
    assertThat(PaymentReferences.suggest("m. 2", LocalDate.of(2026, 9, 1)))
        .isEqualTo(PaymentReferences.suggest("M2", LocalDate.of(2026, 9, 30)));
}

@Test
void aUnitNamedOnlyInPunctuationStillProducesAReference() {
    assertThat(PaymentReferences.suggest("—", LocalDate.of(2026, 9, 1)))
        .isEqualTo("NAJEM/LOKAL/2026-09");
}
```

`PartiesDraftTest`:

```java
@Test
void repeatedParametersBecomeTheTwoLists() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();

    var draft = PartiesDraft.of(List.of(a.toString()), List.of(b.toString()));

    assertThat(draft.tenants()).containsExactly(a);
    assertThat(draft.guarantors()).containsExactly(b);
}

@Test
void aMalformedIdIsRefusedRatherThanDropped() {
    assertThatThrownBy(() -> PartiesDraft.of(List.of("not-a-uuid"), List.of()))
        .isInstanceOf(InvalidContactIdException.class);
}

@Test
void theSamePersonCannotBeAddedTwice() {
    UUID a = UUID.randomUUID();

    assertThatThrownBy(() -> PartiesDraft.of(List.of(a.toString(), a.toString()), List.of()))
        .isInstanceOf(DuplicatePartyException.class);
}

@Test
void theSamePersonCannotGuaranteeTheirOwnTenancy() {
    UUID a = UUID.randomUUID();

    assertThatThrownBy(() -> PartiesDraft.of(List.of(a.toString()), List.of(a.toString())))
        .isInstanceOf(DuplicatePartyException.class);
}

@Test
void absentParametersAreEmptyLists() {
    var draft = PartiesDraft.of(null, null);

    assertThat(draft.tenants()).isEmpty();
    assertThat(draft.guarantors()).isEmpty();
}
```

- [ ] **Step 2: Run and watch them fail**

```
./gradlew :apps:najem-app:test --tests '*PaymentReferencesTest*' --tests '*PartiesDraftTest*'
```

Expected: compilation failure.

- [ ] **Step 3: `PaymentReferences`**

```java
package pl.najem.app.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * A suggested transfer title, which the manager may overwrite.
 *
 * <p><b>It is not unique and does not try to be.</b> Two units named {@code m. 2} in different
 * properties, starting the same month, produce the same string — and reconciliation matches bank
 * lines on it, so a duplicate means one transfer matches two tenancies. {@code UnitBoardQuery.Row}
 * carries no address to discriminate with, and no port exists to ask accounting whether a reference
 * is already taken. Written down here rather than left to be discovered.
 *
 * <p>A suggestion and not a generated value, so an agency migrating tenancies can enter the
 * reference their tenants already quote.
 */
public final class PaymentReferences {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** What a unit whose name is entirely punctuation falls back to, rather than an empty segment. */
    private static final String UNNAMED = "LOKAL";

    private PaymentReferences() {
    }

    public static String suggest(String unitName, LocalDate startDate) {
        String sanitised = unitName == null ? "" : unitName.toUpperCase()
            .replaceAll("[^A-Z0-9]", "");
        return "NAJEM/" + (sanitised.isEmpty() ? UNNAMED : sanitised)
            + "/" + MONTH.format(startDate);
    }
}
```

Note the sanitiser drops Polish diacritics along with punctuation, because `Ł` is not in `A-Z`. That is intended: a transfer title is typed by a tenant into a bank form, and an ASCII one survives every bank's field.

- [ ] **Step 4: `PartiesDraft` and its exception**

```java
package pl.najem.app.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who else is on the reservation, carried in the query string so the screen stays bookmarkable and
 * needs no session.
 *
 * <p>The confirmed lead is NOT here — they come from {@code interestId}, which is why the screen was
 * opened at all. These are the co-tenants and guarantors added on it.
 *
 * <p>A malformed id is refused rather than skipped, on the same grounds as
 * {@link UnitScreenController#chosen}: a garbled value silently dropping a party would produce a
 * reservation naming fewer people than the manager saw on the screen they submitted.
 */
public record PartiesDraft(List<UUID> tenants, List<UUID> guarantors) {

    public static PartiesDraft of(List<String> tenants, List<String> guarantors) {
        Set<UUID> seen = new LinkedHashSet<>();
        return new PartiesDraft(parse(tenants, seen), parse(guarantors, seen));
    }

    private static List<UUID> parse(List<String> raw, Set<UUID> seen) {
        List<UUID> parsed = new ArrayList<>();
        if (raw == null) {
            return List.copyOf(parsed);
        }
        for (String value : raw) {
            UUID id = UnitScreenController.chosen(value)
                .orElseThrow(() -> new InvalidContactIdException(value, null));
            if (!seen.add(id)) {
                throw new DuplicatePartyException(id);
            }
            parsed.add(id);
        }
        return List.copyOf(parsed);
    }
}
```

`chosen` returns empty for blank, and a blank party id is as much a mistake as a malformed one, so both become `InvalidContactIdException`. Check `InvalidContactIdException`'s constructor before writing this — if it does not accept a null cause, add an overload taking only the value rather than passing null.

```java
package pl.najem.app.web;

import java.util.UUID;

/**
 * The same person named twice on one reservation, or named as both tenant and guarantor.
 *
 * <p>Refused here because {@code Tenancy} has no rule against it and would carry the duplicate into
 * {@code TenancyReserved} — where it becomes two tenants who are one person, forever.
 */
public class DuplicatePartyException extends RuntimeException {

    public DuplicatePartyException(UUID contactId) {
        super("Contact " + contactId + " is already named on this reservation");
    }
}
```

Create it at `apps/najem-app/src/main/java/pl/najem/app/web/DuplicatePartyException.java`.

- [ ] **Step 5: Run and commit**

```
./gradlew build
git add -A && git commit -m "Suggest a transfer title, and refuse a party named twice"
```

---

### Task 6: Phase 1 — the parties screen

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/ReserveScreenController.java`
- Create: `apps/najem-app/src/main/resources/templates/reserve-parties.html`
- Modify: `apps/najem-app/src/main/resources/templates/unit.html`
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/WebErrorAdvice.java`

**Interfaces:**
- Consumes: `PartiesDraft.of`, `InterestService.find`-backed lookups, `ContactService.registerParty`, `ContactDirectory.search`/`find`, `UnitBoardQuery.forUnit`, `InterestNotActiveException`, `DuplicatePartyException`.
- Produces: `GET /units/{unitId}/reserve`, `POST /units/{unitId}/reserve/parties`.

- [ ] **Step 1: The Rezerwuj button**

In `unit.html`, in the actions cell of each interested row, beside the existing *Wycofaj* form:

```html
                    <a class="button"
                       th:href="@{/units/{u}/reserve(u=${unit.unitId()}, interestId=${party.interestId()})}">Rezerwuj</a>
```

- [ ] **Step 2: The controller's read**

`ReserveScreenController` holds `UnitBoardQuery`, `UnitInterestQuery`, `ContactDirectory`, `ContactService`, `InterestService`, `TenancyService` and `Clock`. Task 7 uses the last two; declare them now so the constructor is written once.

```java
    @GetMapping("/units/{unitId}/reserve")
    public String reserve(@PathVariable UUID unitId,
                          @RequestParam UUID interestId,
                          @RequestParam(name = "tenant", required = false) List<String> tenants,
                          @RequestParam(name = "guarantor", required = false) List<String> guarantors,
                          @RequestParam(name = "role", required = false) String role,
                          @RequestParam(name = "q", required = false) String term,
                          @RequestParam(name = "parties", required = false) String parties,
                          WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        var unit = units.forUnit(workspaceId, unitId, LocalDate.now(clock))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var lead = interested.activeForUnit(workspaceId, unitId).stream()
            .filter(p -> p.interestId().equals(interestId))
            .findFirst()
            .orElseThrow(() -> new NoSuchInterestException(interestId));
        var draft = PartiesDraft.of(tenants, guarantors);

        model.addAttribute("unit", unit);
        model.addAttribute("lead", lead);
        model.addAttribute("interestId", interestId);
        model.addAttribute("tenants", named(workspaceId, draft.tenants()));
        model.addAttribute("guarantors", named(workspaceId, draft.guarantors()));

        if ("done".equals(parties)) {
            // Task 7 fills this branch in.
            return terms(unit, lead, draft, model);
        }
        model.addAttribute("role", role == null ? "tenant" : role);
        model.addAttribute("term", term);
        model.addAttribute("asked", term != null && !term.isBlank());
        model.addAttribute("hits", directory.search(workspaceId, term));
        return "reserve-parties";
    }
```

`activeForUnit` is the gate: an interest that is withdrawn, converted, foreign or unknown is simply not in the list and the screen 404s, which is the same undifferentiated answer every other id gets here.

`named` resolves each drafted id to a `ContactDetails` for display, 404ing on one this workspace does not know:

```java
    private List<NamedParty> named(UUID workspaceId, List<UUID> ids) {
        return ids.stream()
            .map(id -> new NamedParty(id, directory.find(workspaceId, id)
                .orElseThrow(() -> new NoSuchContactException(id))))
            .toList();
    }

    /** A drafted party with a name to show. The id alone renders as a UUID, which tells nobody anything. */
    public record NamedParty(UUID contactId, ContactDetails details) {
    }
```

- [ ] **Step 3: The inline-creation POST**

```java
    /**
     * A guarantor the agency does not already hold — which is the ordinary case, since a guarantor
     * has no reason to be in the system before the agreement they are guaranteeing.
     *
     * <p>Registered under {@code contract}, not {@code legitimate-interest}: see
     * {@link ContactService#registerParty}. A person created here and then abandoned mid-form is a
     * real orphan — visible on a search and erasable, the same trade the interest form already takes.
     */
    @PostMapping("/units/{unitId}/reserve/parties")
    public String addParty(@PathVariable UUID unitId,
                           @RequestParam UUID interestId,
                           @RequestParam(name = "tenant", required = false) List<String> tenants,
                           @RequestParam(name = "guarantor", required = false) List<String> guarantors,
                           @RequestParam String role,
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

        var draft = PartiesDraft.of(tenants, guarantors);
        // Rebuilt and re-validated rather than appended blindly: the duplicate rule has to hold for
        // the person being added, and PartiesDraft is the only place that rule lives.
        List<String> nextTenants = ids(draft.tenants());
        List<String> nextGuarantors = ids(draft.guarantors());
        ("guarantor".equals(role) ? nextGuarantors : nextTenants).add(added.toString());
        PartiesDraft.of(nextTenants, nextGuarantors);

        return "redirect:" + UriComponentsBuilder.fromPath("/units/{unitId}/reserve")
            .queryParam("interestId", interestId)
            .queryParam("tenant", nextTenants)
            .queryParam("guarantor", nextGuarantors)
            .queryParam("role", role)
            .buildAndExpand(unitId).toUriString();
    }

    private static List<String> ids(List<UUID> parties) {
        return parties.stream().map(UUID::toString).collect(Collectors.toCollection(ArrayList::new));
    }
```

The re-validation call's result is deliberately discarded — it runs for its exception. If that reads as dead code to a reviewer, assign it to a local named `validated` and use it to build the lists instead.

- [ ] **Step 4: `reserve-parties.html`**

Header: crumb back to `/units/{unitId}`, `h1` naming the unit, and the lead shown as first tenant with the note that they cannot be removed.

Then, for each of the two roles, the confirmed list with a *Usuń* link (a GET back to the same screen minus that id) and, under whichever role `role` names, the unit screen's search-then-create block:

- a GET form to `/units/{unitId}/reserve` carrying `interestId`, every `tenant`, every `guarantor`, `role`, and the `q` box
- hits as links that add `&{role}=<contactId>` — build them with `th:href="@{...}"`
- an *albo nowa osoba* POST form to `/units/{unitId}/reserve/parties` with hidden `interestId`, `role`, and every drafted `tenant`/`guarantor`, plus the four text fields and the `infoClauseServed` checkbox

Finish with a *Dalej* link to the same URL plus `&parties=done`.

Copy the wording and structure from `unit.html`'s `section.lead` — including the "type something" versus "nobody matched" distinction, which must not share a message.

- [ ] **Step 5: Map the new exceptions**

In `WebErrorAdvice`:

```java
    /**
     * The interest exists and is yours, but has already been withdrawn or converted — most often a
     * stale tab, or the back button after reserving. A 404 would be false, because the caller can
     * see it on their own screen.
     */
    @ExceptionHandler(InterestNotActiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void interestClosed() {
    }

    @ExceptionHandler(DuplicatePartyException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void duplicateParty() {
    }
```

- [ ] **Step 6: Run and commit**

```
./gradlew build
git add -A && git commit -m "Let a manager name who else is on the reservation"
```

---

### Task 7: Phase 2 — the terms, and the write

**Files:**
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/ReserveScreenController.java`
- Create: `apps/najem-app/src/main/resources/templates/reserve-terms.html`
- Modify: `apps/najem-app/src/main/resources/templates/timeline.html`

**Interfaces:**
- Consumes: everything from Tasks 1-6, plus `TenancyService.reserve`, `InterestService.convert`, `ContactService.becameContractParty`.
- Produces: `POST /units/{unitId}/reserve`.

- [ ] **Step 1: The terms branch**

`terms(...)` puts the unit, the lead, the named parties, the suggested reference
(`PaymentReferences.suggest(unit.name(), lead.desiredStart() != null ? lead.desiredStart() : LocalDate.now(clock))`),
a default `startDate` of the lead's `desiredStart` or today, a default `monthlyTotal` of the lead's
`willingToPay` or the unit's `baseRent`, `rentDay` 10, and `LegalForm.values()` into the model, then
returns `"reserve-terms"`.

Defaults come from what somebody said where anything did — the lead's own figures before the unit's
asking price — because a prefilled number the manager did not say is the thing they are least likely
to check.

- [ ] **Step 2: The write**

```java
    /**
     * The agreement is signed.
     *
     * <p>The reservation goes first because it is the fact, and it is the only step that can fail
     * hard. A failure after it leaves a real tenancy beside a stale lead — visible on the unit screen
     * and fixable. Converting first would mark a lead as won for a tenancy that does not exist, and
     * nothing would show that.
     */
    @PostMapping("/units/{unitId}/reserve")
    public String create(@PathVariable UUID unitId,
                         @RequestParam UUID interestId,
                         @RequestParam(name = "tenant", required = false) List<String> tenants,
                         @RequestParam(name = "guarantor", required = false) List<String> guarantors,
                         @RequestParam String legalForm,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                         @RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                         @RequestParam BigDecimal monthlyTotal,
                         @RequestParam(defaultValue = "false") boolean componentSplit,
                         @RequestParam(required = false) BigDecimal rent,
                         @RequestParam(required = false) BigDecimal adminFee,
                         @RequestParam(required = false) BigDecimal mediaAdvance,
                         @RequestParam int rentDay,
                         @RequestParam(required = false) BigDecimal depositAmount,
                         @RequestParam String paymentReference,
                         WebWorkspace workspace, Model model,
                         RedirectAttributes flash) {
        UUID workspaceId = workspace.workspaceId();
        var lead = /* same activeForUnit lookup as the GET */;
        var draft = PartiesDraft.of(tenants, guarantors);

        List<UUID> allTenants = new ArrayList<>();
        allTenants.add(lead.contactId());
        allTenants.addAll(draft.tenants());

        var command = new ReserveTenancy(null, workspaceId, unitId, allTenants, draft.guarantors(),
            startDate, endDate == null ? new Term.Indefinite() : new Term.FixedTerm(endDate),
            LegalForm.valueOf(legalForm.toUpperCase()),
            new MonthlyAmount(monthlyTotal, componentSplit
                ? new MonthlyAmount.Breakdown(rent, adminFee, mediaAdvance) : null),
            rentDay, depositAmount, paymentReference);

        Reservation reservation;
        try {
            reservation = tenancies.reserve(workspaceId, command);
        } catch (OverlappingTenancyException e) {
            // The manager's input caused this, and an error page would be correct and would also
            // throw away the form. Re-render with everything they typed still in its field.
            model.addAttribute("error", e.getMessage());
            return termsAgain(workspaceId, unitId, interestId, draft, /* submitted values */, model);
        }

        LocalDate today = LocalDate.now(clock);
        interests.convert(workspaceId, interestId, reservation.tenancyId(), today);
        allTenants.forEach(id -> contacts.becameContractParty(workspaceId, id, today));
        draft.guarantors().forEach(id -> contacts.becameContractParty(workspaceId, id, today));

        flash.addFlashAttribute("warnings", reservation.warnings());
        return "redirect:/tenancies/" + reservation.tenancyId() + "/timeline";
    }
```

`ReserveTenancy`'s first field is the tenancy id and `withWorkspaceOf` generates one when it is null,
so passing null is the supported path, not an omission.

`termsAgain` renders `reserve-terms` from the submitted values rather than the defaults — write it as
a private method taking them, and have step 1's `terms(...)` delegate to it with the defaults, so one
method builds that model and the re-render cannot drift from the first render.

- [ ] **Step 3: `reserve-terms.html`**

One POST form to `/units/{unitId}/reserve` with hidden `interestId` and every drafted `tenant` and
`guarantor`. Above it, the named parties as read-only text. Fields, in this order and all Polish:

| field | control | note |
| --- | --- | --- |
| `legalForm` | radios over `LegalForm.values()` | no blank option — `Tenancy.reserve` refuses a null form and there is no default worth guessing |
| `startDate` | `type="date"`, required | |
| `endDate` | radio *na czas nieokreślony* / *do* + `type="date"` | absent means indefinite |
| `monthlyTotal` | text, `inputmode="decimal"`, required | |
| `componentSplit` | checkbox | `value="true"`; reveals the three below |
| `rent`, `adminFee`, `mediaAdvance` | text, `inputmode="decimal"` | |
| `rentDay` | `type="number"` min 1 max 28, required | |
| `depositAmount` | text, `inputmode="decimal"` | |
| `paymentReference` | text, required, prefilled | |

Render `${error}` above the form when present, in the style of whatever error block already exists in
the templates — check `bank.html` first.

- [ ] **Step 4: Show the warnings on the timeline**

In `timeline.html`, above the entries:

```html
    <!--/*
      Reserve()'s soft checks -- a deposit over the statutory cap, a breakdown that does not sum, a
      term past ten years. Shown once, at the moment the manager made the decision, because a
      statutory flag on a report a month later is decorative.
    */-->
    <ul class="warnings" th:if="${warnings != null and !warnings.isEmpty()}">
        <li th:each="warning : ${warnings}" th:text="${warning}">—</li>
    </ul>
```

- [ ] **Step 5: Run and commit**

```
./gradlew build
git add -A && git commit -m "Turn a confirmed lead into a signed reservation"
```

---

### Task 8: Cancelling a reservation

**Files:**
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/TimelineScreenController.java`
- Modify: `apps/najem-app/src/main/resources/templates/timeline.html`
- Modify: `apps/najem-app/src/main/java/pl/najem/app/web/WebErrorAdvice.java`

- [ ] **Step 1: Offer the button only when it will work**

In `TimelineScreenController.tenancy`, after building the model:

```java
        model.addAttribute("cancellableTenancyId",
            tenancies.isReserved(workspace.workspaceId(), tenancyId) ? tenancyId : null);
```

The unit-level `timeline` method must not set it — a unit is not cancellable, and the shared template
would otherwise offer a button pointing at a unit id.

- [ ] **Step 2: The POST**

```java
    /**
     * A reservation made by mistake. Managers can now create these from a screen, so an undo that
     * exists only in the API is an undo nobody has.
     *
     * <p>The interest is deliberately NOT un-converted. The lead did sign and the reservation was
     * then undone; rewriting their history to say otherwise would lose that. A manager who wants them
     * back on the unit's list registers a fresh interest.
     */
    @PostMapping("/tenancies/{tenancyId}/cancel")
    public String cancel(@PathVariable UUID tenancyId, @RequestParam(required = false) String reason,
                         WebWorkspace workspace) {
        tenancies.cancelReservation(workspace.workspaceId(), tenancyId, reason == null ? "" : reason);
        return "redirect:/tenancies/" + tenancyId + "/timeline";
    }
```

- [ ] **Step 3: The button**

```html
    <form th:if="${cancellableTenancyId != null}" method="post"
          th:action="@{/tenancies/{id}/cancel(id=${cancellableTenancyId})}">
        <input type="text" name="reason" placeholder="Powód">
        <button type="submit">Anuluj rezerwację</button>
    </form>
```

- [ ] **Step 4: Map the refusal**

`cancelReservation` throws `IllegalStateException` for a tenancy that is not reserved. The button is
not offered for one, but a stale tab can still post it. A bare `IllegalStateException` handler would
turn genuine bugs into tidy 409s, so map only `OverlappingTenancyException`'s sibling if PM has a
dedicated type; otherwise **change `Tenancy.cancelReservation` to throw a new
`pl.najem.pm.domain.NotReservedException extends IllegalStateException`** carrying the same message,
and map that. Check `PmExceptionHandler` first and follow whatever it already does with these.

- [ ] **Step 5: Run and commit**

```
./gradlew build
git add -A && git commit -m "Give a mistaken reservation an undo the manager can reach"
```

---

### Task 9: The integration tier

**Files:**
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/ReserveScreenTest.java`

Model it on `UnitScreenTest`, which already boots a container with `@ServiceConnection`, runs
`permit-all`, and drains projections with `projections.runOnce()` rather than sleeping. Read it first
and match its setup exactly.

- [ ] **Step 1: The scenarios**

Five tests. Each drives MockMvc through the real screens and then asserts against the **rows** with
`JdbcTemplate`, not only against the response — a 302 proves a redirect, not a write.

**`reservingFromALeadCreatesTheTenancyAndClosesTheInterest`** — register a property, a unit and a
lead's interest; `projections.runOnce()`; POST the terms form. Assert: exactly one `pm_tenancy` row
for that unit with `state = 'RESERVED'` and the posted `payment_reference`; that interest's
`contacts_interest.status` is `'converted'` and its `converted_to_tenancy_id` equals the new tenancy
id; and `GET /units/{unitId}` no longer contains the lead's surname.

**`aGuarantorCreatedOnTheFormIsHeldUnderContract`** — POST `/units/{id}/reserve/parties` with
`role=guarantor` and the four new-person fields, follow the redirect, then POST the terms carrying
the returned `guarantor` id. Assert: a `contacts_person` row for that surname with
`lawful_basis = 'contract'`, and the guarantor's id present in the `TenancyReserved` event loaded
from the store for the new tenancy.

**`theLeadsLawfulBasisBecomesContract`** — same setup as the first; assert the lead's
`contacts_person.lawful_basis` is `'legitimate-interest'` **before** the POST and `'contract'`
after. Asserting only the after-state would pass against a fixture that was never a lead.

**`reservingOverAnExistingTenancyRendersTheErrorAndCreatesNothing`** — reserve once, then post a
second reservation for the same unit over an overlapping period from a second lead. Assert: status
200 (a re-render, not a redirect), the response body contains the model attribute `error`, and the
counts of `pm_tenancy` rows, `contacts_interest` rows with status `'converted'`, and
`contacts_person` rows with `lawful_basis = 'contract'` are all unchanged from before the POST.
Capture those three counts into locals before the POST and compare — a hardcoded expected count
would pass for the wrong reason if the fixture changed.

**`cancellingAReservationFreesTheUnitAndRefusesASecondCancel`** — reserve, POST
`/tenancies/{id}/cancel`, `projections.runOnce()`. Assert: `GET /units/{unitId}` shows the unit with
no upcoming tenancy again, and a second POST to the same cancel URL returns 409.

- [ ] **Step 2: Run the slow tier**

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
./gradlew build -PintegrationTests
```

This takes roughly eight minutes. Do not shorten it by dropping the tag.

- [ ] **Step 3: Mutate two tripwires (rule 21)**

Break `PartiesDraft`'s duplicate check and confirm a test goes red. Break the ordering in
`create` so `convert` runs before `reserve`, and confirm the overlap test goes red on a converted
interest with no tenancy. Revert both and record them in the report.

- [ ] **Step 4: Commit**

```
git add -A && git commit -m "Check the reservation path against the rows it writes"
```

## Self-Review

**Spec coverage.** Every section of the spec maps to a task: the two events to Tasks 2 and 3, the
state machine to Task 1, `isReserved` to Task 4, the two phases to Tasks 6 and 7, cancel to Task 8,
both test tiers to Tasks 1-9. The reference collision and the "lead is not in the tenant list" rule
are both pinned by named tests in Task 5.

**Placeholders.** Two remain and are marked as such rather than hidden: the `terms(...)` model in
Task 7 Step 1 is described field by field rather than shown as code, and `create`'s lead lookup says
"same as the GET". Both are transcriptions of code written earlier in the same file. The
`termsAgain` signature is left to the implementer because its parameter list is exactly the form's
fields, which the table in Step 3 enumerates.

**Type consistency.** `PartiesDraft.of(List<String>, List<String>)` is called identically in Tasks 6
and 7. `InterestRepository.find` is introduced in Task 1 and used in Task 2. `registerParty` and
`becameContractParty` are defined in Task 3 and used in Tasks 6 and 7. `isReserved` is defined in
Task 4 and used in Task 8. `InterestedParty.contactId()` and `.interestId()` already exist.

**One risk worth stating.** Task 8 Step 4 may require a change to `Tenancy.cancelReservation`'s
exception type, which is a PM domain change arriving in an app-layer task. If the implementer finds
`PmExceptionHandler` already maps `IllegalStateException`, they should follow that instead and say
so — the point is that no bare `IllegalStateException` handler is added to `WebErrorAdvice`.
