# Contacts Module (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `modules/contacts` — the person registry for tenants, guarantors and leads — with the decided PII-lookaside architecture: events carry identifiers only, all personal data lives in an erasable Postgres table, and right-to-be-forgotten is a row deletion that leaves the event stream intact.

**Architecture:** Hexagonal, same shape as the landed `modules/accounting`: `domain/` holds event records, `application/` holds transactional services that append to the event store and maintain SQL projections, `adapter/rest/` exposes HTTP. Contacts is referenced by other modules **only by `ContactId`** — there is no cross-module Java port and no compile dependency in either direction (roadmap rule 2). Erasure is gated by a retention-hold register that Contacts owns; accounting's `RetentionHoldSet/Released` will feed it via a later, jointly-raised contract change.

**Tech Stack:** Java 21, Spring (spring-context / spring-tx / spring-jdbc / spring-web, no Boot starters inside modules), `pl.najem.eventstore.EventStore` (jsonb + optimistic concurrency), Flyway, JUnit 5 + AssertJ + Testcontainers Postgres 16.

## Global Constraints

Copied verbatim from the coordinator's PHASE 0 GATE post (najem-build seq 15) — these apply to every task below.

- Build env, or tests fail confusingly:
  ```bash
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  export DOCKER_HOST="unix:///Users/jaca/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
  ```
- Do **NOT** touch: the testcontainers-bom pin (1.21.3), `systemProperty api.version=1.44`, or the `-parameters` compiler flag in the root `build.gradle.kts`.
- Flyway: this module owns `classpath:db/contacts`. **Claimed version: V4** (announced on najem-build seq 17). `apps/najem-app/src/main/resources/application.yml` already lists `classpath:db/contacts` — no composition-root edit needed for migrations.
- Every new event class must be registered in `ContactsEventTypes.register()`.
- Adapters own wire DTOs — never deserialize external JSON straight into application records.
- New **integration** events (anything in `contracts/`) = CONTRACT-CHANGE-REQUEST on najem-build, wait for coordinator ACK. This plan deliberately introduces **none**.
- Branch `najem-contacts/phase1-contacts`; rebase main often; post STATUS (start / done+commit-hash / blocker) to najem-build. Coordinator reviews and merges.
- **Standing hold (najem-usermgmt, seq 12/16):** do NOT scope any table or stream by `WorkspaceId` until the human answers whether a workspace is the multi-tenancy boundary. Build single-tenant-shaped. If the answer is YES, V4 gets an additive follow-up migration adding `workspace_id` and changing duplicate-detection from global to per-workspace.
- No PII in events. Ever. A reviewer should be able to `grep` the `domain/` package and find no name, email or phone field.

## Design decisions (and why), for the reviewer

1. **What counts as PII here.** `given_name`, `surname`, `email`, `phone` live only in `contacts_person` and never enter an event payload. Commercial attributes on an interest (`willing_to_pay`, `desired_start`) DO live in events, keyed by `ContactId`: once the person row is deleted, those events no longer identify anybody. That is the pseudonymisation argument the lookaside decision rests on. If the reviewer disagrees, the fix is to move those two fields into the projection table only — a one-task change, so it is worth settling at plan review rather than after.
2. **Erasure = delete the row, log the tombstone.** `contacts_person` row is deleted outright (per B5, "right-to-be-forgotten = row deletion"). A `contacts_erasure_log(contact_id, erased_on)` row records *that* an erasure happened, holding no personal data — without it we cannot distinguish "erased" from "never existed", which matters for audit and for the erasure-due report.
3. **Retention duration is unresolved (hotspot #15, "ask domain expert").** This plan does not invent a legal answer: `retainUntil` is supplied by the caller, and when absent it is computed from a configurable `najem.contacts.lead-retention-days` whose default of 365 is explicitly **provisional**. The erasure-due endpoint *reports* contacts past their date; it never deletes on its own. A manager confirms. No scheduled auto-deletion in Phase 1.
4. **Holds beat erasure.** Accounting's `RetentionHoldSet/Released` (~5–6y tax + civil prescription) means a ledger-referenced contact must not be erasable. Contacts owns the register and refuses erasure while any hold is unreleased. Phase 1 exposes hold set/release over REST; wiring accounting's events into it is a follow-up contract change to be raised jointly with najem-accounting.
5. **No cross-module port.** PM references contact IDs (`property-management-domain-model.md:258`, Customer–Supplier). Resolving an ID to a display name is a *UI composition* concern and happens over REST in the Phase 2 frontend, not through a Java interface that would breach module isolation.

## File Structure

**Created:**
- `settings.gradle.kts` (modify) — add `"modules:contacts"` to the `include(...)` list.
- `apps/najem-app/build.gradle.kts` (modify) — add `implementation(project(":modules:contacts"))`.
- `modules/contacts/build.gradle.kts` — module deps, mirrors `modules/accounting/build.gradle.kts`.
- `modules/contacts/src/main/resources/db/contacts/V4__contacts.sql` — four tables.
- `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java` — event-type registration.
- `modules/contacts/src/main/java/pl/najem/contacts/domain/` — one record per event: `ContactRegistered`, `ContactDetailsCorrected`, `InterestRegistered`, `InterestWithdrawn`, `RetentionHoldSet`, `RetentionHoldReleased`, `ContactErased`.
- `modules/contacts/src/main/java/pl/najem/contacts/application/ContactDetails.java`, `NewContact.java` — value records (PII carriers, never persisted to the event store).
- `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java` — register / correct / erase.
- `modules/contacts/src/main/java/pl/najem/contacts/application/ContactDirectory.java` — read side (resolve by id).
- `modules/contacts/src/main/java/pl/najem/contacts/application/InterestService.java` — register / withdraw / by-unit query.
- `modules/contacts/src/main/java/pl/najem/contacts/application/RetentionService.java` — holds + erasure-due report.
- `modules/contacts/src/main/java/pl/najem/contacts/application/RetentionHoldActiveException.java`.
- `modules/contacts/src/main/java/pl/najem/contacts/adapter/rest/ContactsController.java`, `InterestsController.java`, `RetentionController.java` — wire DTOs live here.
- Tests mirroring each service under `modules/contacts/src/test/java/pl/najem/contacts/application/`.
- `e2e/src/test/java/pl/najem/e2e/ContactLifecycleTest.java` — appended e2e scenario (roadmap rule 4).

---

### Task 1: Module scaffold, schema, event registration

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `apps/najem-app/build.gradle.kts`
- Create: `modules/contacts/build.gradle.kts`
- Create: `modules/contacts/src/main/resources/db/contacts/V4__contacts.sql`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/ContactRegistered.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/SchemaTest.java`

**Interfaces:**
- Consumes: `pl.najem.eventstore.EventTypeRegistry` (Phase 0, frozen).
- Produces: the `contacts_person` / `contacts_interest` / `contacts_retention_hold` / `contacts_erasure_log` tables and `ContactsEventTypes.register(EventTypeRegistry)` — every later task adds its event record to that method.

- [ ] **Step 1: Write the failing test**

```java
package pl.najem.contacts;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SchemaTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test
    void migratesContactsSchemaAndHoldsNoPiiOutsideThePersonTable() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public' and table_name like 'contacts%'
            order by table_name
            """, String.class))
            .containsExactly("contacts_erasure_log", "contacts_interest",
                "contacts_person", "contacts_retention_hold");

        assertThat(jdbc.queryForList("""
            select table_name || '.' || column_name from information_schema.columns
            where table_schema = 'public' and table_name like 'contacts%'
              and column_name in ('given_name', 'surname', 'email', 'phone')
            """, String.class))
            .allSatisfy(column -> assertThat(column).startsWith("contacts_person."));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*SchemaTest*'`
Expected: FAIL — the project `:modules:contacts` does not exist yet ("Project 'contacts' not found").

- [ ] **Step 3: Create the build scaffold**

`settings.gradle.kts` — add one line to the existing `include(...)`, keeping alphabetical-ish grouping with the other modules:

```kotlin
    "modules:contacts",
```

`apps/najem-app/build.gradle.kts` — add next to the other module deps:

```kotlin
    implementation(project(":modules:contacts"))
```

`modules/contacts/build.gradle.kts`:

```kotlin
plugins { `java-library` }

dependencies {
    api(project(":contracts"))
    implementation(project(":platform:eventstore"))
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
```

- [ ] **Step 4: Write the migration**

`modules/contacts/src/main/resources/db/contacts/V4__contacts.sql`:

```sql
-- PII lookaside: the ONLY table in the system holding personal data.
-- Right-to-be-forgotten = delete the row here; event streams stay untouched.
create table contacts_person (
  contact_id            uuid primary key,
  given_name            text not null,
  surname               text not null,
  email                 text,
  phone                 text,
  lawful_basis          text not null,
  info_clause_served_at date,
  retain_until          date
);

-- Tombstone: records THAT an erasure happened. Holds no personal data.
create table contacts_erasure_log (
  contact_id uuid primary key,
  erased_on  date not null
);

-- Lead = contact + interest link to unit(s). Deliberately thin, no CRM.
create table contacts_interest (
  interest_id    uuid primary key,
  contact_id     uuid not null,
  unit_id        uuid not null,
  willing_to_pay numeric,
  desired_start  date,
  status         text not null default 'active'
);

create index contacts_interest_by_unit on contacts_interest (unit_id);
create index contacts_interest_by_contact on contacts_interest (contact_id);

-- Erasure gate. Accounting's ~5-6y tax/civil-prescription holds land here.
create table contacts_retention_hold (
  contact_id  uuid not null,
  reason      text not null,
  set_on      date not null,
  released_on date,
  primary key (contact_id, reason)
);
```

- [ ] **Step 5: Write the first event record and the registry**

`domain/ContactRegistered.java` — note there is no name, email or phone in it:

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactRegistered(UUID contactId, String lawfulBasis,
                                LocalDate infoClauseServedAt, LocalDate retainUntil) {
}
```

`ContactsEventTypes.java`:

```java
package pl.najem.contacts;

import org.springframework.stereotype.Component;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventTypeRegistry;

@Component
public class ContactsEventTypes {

    public ContactsEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(ContactRegistered.class);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :modules:contacts:test --tests '*SchemaTest*'`
Expected: PASS

- [ ] **Step 7: Verify the whole build still goes green**

Run: `./gradlew build`
Expected: PASS, including `e2e:test` (WalkingSkeletonTest). A new empty module must not disturb the skeleton.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts apps/najem-app/build.gradle.kts modules/contacts
git commit -m "Add contacts module scaffold with PII lookaside schema"
```

---

### Task 2: Register a contact (PII into the lookaside, IDs into the event)

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactDetails.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/NewContact.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactServiceTest.java`

**Interfaces:**
- Consumes: `EventStore.load(UUID)` / `EventStore.append(UUID streamId, String type, long expectedVersion, List<Object> events, List<Object> integrationEvents)`, `ContactsEventTypes.register` (Task 1).
- Produces: `ContactDetails(String givenName, String surname, String email, String phone)`; `NewContact(ContactDetails details, String lawfulBasis, LocalDate infoClauseServedAt, LocalDate retainUntil)`; `ContactService.register(NewContact) -> UUID`. Stream type string is `"Contact"`, stream id is the contact id — every later task appends to that same stream.

- [ ] **Step 1: Write the failing test**

The two assertions that matter: the projection row carries the PII, and the event carries none of it.

```java
package pl.najem.contacts.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.ContactsEventTypes;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ContactServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContactService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        service = new ContactService(store, jdbc);
    }

    @Test
    void storesPersonalDataInTheLookasideAndOnlyIdentifiersInTheEvent() {
        var anna = new NewContact(
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), LocalDate.of(2027, 8, 3));

        var contactId = service.register(anna);

        assertThat(store.load(contactId).events()).containsExactly(
            new ContactRegistered(contactId, "legitimate-interest",
                LocalDate.of(2026, 8, 3), LocalDate.of(2027, 8, 3)));
        assertThat(jdbc.queryForMap("select * from contacts_person where contact_id = ?", contactId))
            .containsEntry("given_name", "Anna")
            .containsEntry("surname", "Kowalska")
            .containsEntry("email", "anna@example.com")
            .containsEntry("phone", "+48600100200");
    }

    @Test
    void keepsPersonalDataOutOfTheEventStorePayload() {
        var contactId = service.register(new NewContact(
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", "+48600300400"),
            "contract", LocalDate.of(2026, 8, 3), null));

        var payloads = jdbc.queryForList(
            "select payload::text from event where stream_id = ?", String.class, contactId);

        assertThat(payloads).isNotEmpty();
        assertThat(payloads).noneSatisfy(payload ->
            assertThat(payload).containsAnyOf("Piotr", "Nowak", "piotr@example.com", "+48600300400"));
    }
}
```

> If the event-store table or payload column is not named `event` / `payload`, read `platform/eventstore/src/main/resources/db/eventstore/V1__eventstore.sql` and use the real names. Do not change the event store — it is frozen.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*ContactServiceTest*'`
Expected: FAIL — `ContactService` does not exist (compilation error).

- [ ] **Step 3: Write the value records**

```java
package pl.najem.contacts.application;

public record ContactDetails(String givenName, String surname, String email, String phone) {
}
```

```java
package pl.najem.contacts.application;

import java.time.LocalDate;

public record NewContact(ContactDetails details, String lawfulBasis,
                         LocalDate infoClauseServedAt, LocalDate retainUntil) {
}
```

- [ ] **Step 4: Write the minimal implementation**

```java
package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventStore;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ContactService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public ContactService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID register(NewContact contact) {
        UUID contactId = UUID.randomUUID();
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactRegistered(contactId, contact.lawfulBasis(),
                contact.infoClauseServedAt(), contact.retainUntil())), List.of());
        jdbc.update("""
            insert into contacts_person(contact_id, given_name, surname, email, phone,
                                        lawful_basis, info_clause_served_at, retain_until)
            values (?,?,?,?,?,?,?,?)
            """,
            contactId, contact.details().givenName(), contact.details().surname(),
            contact.details().email(), contact.details().phone(),
            contact.lawfulBasis(), contact.infoClauseServedAt(), contact.retainUntil());
        return contactId;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :modules:contacts:test --tests '*ContactServiceTest*'`
Expected: PASS (both tests)

- [ ] **Step 6: Commit**

```bash
git add modules/contacts
git commit -m "Register contacts with personal data in the lookaside table"
```

---

### Task 3: Resolve and correct contact details

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactDirectory.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/ContactDetailsCorrected.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactDirectoryTest.java`

**Interfaces:**
- Consumes: `ContactService.register` (Task 2).
- Produces: `ContactDirectory.find(UUID) -> Optional<ContactDetails>`; `ContactService.correctDetails(UUID contactId, ContactDetails details, LocalDate correctedOn)`. The REST layer (Task 6) and the e2e test (Task 7) both call these.

- [ ] **Step 1: Write the failing test**

```java
package pl.najem.contacts.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.ContactsEventTypes;
import pl.najem.contacts.domain.ContactDetailsCorrected;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ContactDirectoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContactService service;
    static ContactDirectory directory;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        service = new ContactService(store, jdbc);
        directory = new ContactDirectory(jdbc);
    }

    @Test
    void resolvesRegisteredContactById() {
        var contactId = service.register(new NewContact(
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));

        assertThat(directory.find(contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"));
    }

    @Test
    void returnsEmptyForUnknownContact() {
        assertThat(directory.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void correctsDetailsWithoutLeakingThemIntoTheEvent() {
        var contactId = service.register(new NewContact(
            new ContactDetails("Ana", "Kowalsk", "ana@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));

        service.correctDetails(contactId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100201"),
            LocalDate.of(2026, 8, 4));

        assertThat(directory.find(contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100201"));
        assertThat(store.load(contactId).events())
            .contains(new ContactDetailsCorrected(contactId, LocalDate.of(2026, 8, 4)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*ContactDirectoryTest*'`
Expected: FAIL — `ContactDirectory` and `ContactDetailsCorrected` do not exist.

- [ ] **Step 3: Write the event record and register it**

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactDetailsCorrected(UUID contactId, LocalDate correctedOn) {
}
```

In `ContactsEventTypes.register`, add:

```java
        registry.register(ContactDetailsCorrected.class);
```

- [ ] **Step 4: Write the directory**

```java
package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class ContactDirectory {

    private final JdbcTemplate jdbc;

    public ContactDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ContactDetails> find(UUID contactId) {
        return jdbc.query("""
                select given_name, surname, email, phone from contacts_person where contact_id = ?
                """,
                (rs, i) -> new ContactDetails(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                contactId)
            .stream().findFirst();
    }
}
```

- [ ] **Step 5: Add the correction command to `ContactService`**

```java
    public void correctDetails(UUID contactId, ContactDetails details, LocalDate correctedOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactDetailsCorrected(contactId, correctedOn)), List.of());
        jdbc.update("""
            update contacts_person set given_name = ?, surname = ?, email = ?, phone = ?
            where contact_id = ?
            """,
            details.givenName(), details.surname(), details.email(), details.phone(), contactId);
    }
```

Add the imports `pl.najem.contacts.domain.ContactDetailsCorrected` and `java.time.LocalDate`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :modules:contacts:test --tests '*ContactDirectoryTest*'`
Expected: PASS (three tests)

- [ ] **Step 7: Commit**

```bash
git add modules/contacts
git commit -m "Resolve and correct contact details through the lookaside"
```

---

### Task 4: Unit interests (the lead link)

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/InterestRegistered.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/InterestWithdrawn.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/Interest.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/InterestService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/InterestServiceTest.java`

**Interfaces:**
- Consumes: `ContactService.register` (Task 2).
- Produces: `Interest(UUID interestId, UUID contactId, UUID unitId, BigDecimal willingToPay, LocalDate desiredStart, String status)`; `InterestService.register(UUID contactId, UUID unitId, BigDecimal willingToPay, LocalDate desiredStart) -> UUID`; `InterestService.withdraw(UUID interestId, LocalDate withdrawnOn)`; `InterestService.forUnit(UUID unitId) -> List<Interest>`.

A contact may be interested in many units (`property-management-domain-model.md:253`). One interest per (contact, unit) is NOT enforced — a manager re-registering interest after withdrawal is normal, and the domain doc asks for thinness, not uniqueness.

- [ ] **Step 1: Write the failing test**

```java
package pl.najem.contacts.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.ContactsEventTypes;
import pl.najem.contacts.domain.InterestRegistered;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InterestServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ContactService contacts;
    static InterestService interests;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        contacts = new ContactService(store, jdbc);
        interests = new InterestService(store, jdbc);
    }

    private static UUID aContact() {
        return contacts.register(new NewContact(
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));
    }

    @Test
    void registersInterestInSeveralUnitsForOneContact() {
        var contactId = aContact();
        var unit12 = UUID.randomUUID();
        var unit14 = UUID.randomUUID();

        var first = interests.register(contactId, unit12, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));
        interests.register(contactId, unit14, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.forUnit(unit12)).containsExactly(
            new Interest(first, contactId, unit12, new BigDecimal("2400"), LocalDate.of(2026, 10, 1), "active"));
        assertThat(interests.forUnit(unit14)).hasSize(1);
    }

    @Test
    void withdrawnInterestDropsOutOfTheUnitView() {
        var contactId = aContact();
        var unitId = UUID.randomUUID();
        var interestId = interests.register(contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        interests.withdraw(interestId, LocalDate.of(2026, 9, 1));

        assertThat(interests.forUnit(unitId)).isEmpty();
    }

    @Test
    void recordsInterestOnTheContactStream() {
        var contactId = aContact();
        var unitId = UUID.randomUUID();

        var interestId = interests.register(contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.eventsFor(contactId)).contains(
            new InterestRegistered(interestId, contactId, unitId,
                new BigDecimal("2400"), LocalDate.of(2026, 10, 1)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*InterestServiceTest*'`
Expected: FAIL — `InterestService`, `Interest`, `InterestRegistered` do not exist.

- [ ] **Step 3: Write the event records and register them**

```java
package pl.najem.contacts.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InterestRegistered(UUID interestId, UUID contactId, UUID unitId,
                                 BigDecimal willingToPay, LocalDate desiredStart) {
}
```

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record InterestWithdrawn(UUID interestId, UUID contactId, LocalDate withdrawnOn) {
}
```

In `ContactsEventTypes.register`, add:

```java
        registry.register(InterestRegistered.class);
        registry.register(InterestWithdrawn.class);
```

- [ ] **Step 4: Write the projection record and the service**

```java
package pl.najem.contacts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Interest(UUID interestId, UUID contactId, UUID unitId,
                       BigDecimal willingToPay, LocalDate desiredStart, String status) {
}
```

```java
package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.InterestRegistered;
import pl.najem.contacts.domain.InterestWithdrawn;
import pl.najem.eventstore.EventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class InterestService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public InterestService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID register(UUID contactId, UUID unitId, BigDecimal willingToPay, LocalDate desiredStart) {
        UUID interestId = UUID.randomUUID();
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestRegistered(interestId, contactId, unitId, willingToPay, desiredStart)), List.of());
        jdbc.update("""
            insert into contacts_interest(interest_id, contact_id, unit_id, willing_to_pay, desired_start, status)
            values (?,?,?,?,?, 'active')
            """, interestId, contactId, unitId, willingToPay, desiredStart);
        return interestId;
    }

    public void withdraw(UUID interestId, LocalDate withdrawnOn) {
        UUID contactId = jdbc.queryForObject(
            "select contact_id from contacts_interest where interest_id = ?", UUID.class, interestId);
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestWithdrawn(interestId, contactId, withdrawnOn)), List.of());
        jdbc.update("update contacts_interest set status = 'withdrawn' where interest_id = ?", interestId);
    }

    public List<Interest> forUnit(UUID unitId) {
        return jdbc.query("""
            select interest_id, contact_id, unit_id, willing_to_pay, desired_start, status
            from contacts_interest where unit_id = ? and status = 'active' order by interest_id
            """,
            (rs, i) -> new Interest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class), rs.getString(6)),
            unitId);
    }

    public List<Object> eventsFor(UUID contactId) {
        return List.copyOf(store.load(contactId).events());
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :modules:contacts:test --tests '*InterestServiceTest*'`
Expected: PASS (three tests)

If `containsExactly` on `Interest` fails only on the `BigDecimal` scale (`2400` vs `2400.00`), the numeric column round-trips with a scale. Fix the assertion with `assertThat(...).usingRecursiveComparison().ignoringFields("willingToPay")` plus an explicit `compareTo` check — do **not** paper over it by weakening the whole assertion.

- [ ] **Step 6: Commit**

```bash
git add modules/contacts
git commit -m "Link contacts to units of interest"
```

---

### Task 5: Retention holds and erasure

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/RetentionHoldSet.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/RetentionHoldReleased.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/ContactErased.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/RetentionHoldActiveException.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/RetentionService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/RetentionServiceTest.java`

**Interfaces:**
- Consumes: `ContactService.register` (Task 2), `ContactDirectory.find` (Task 3), `InterestService.register` (Task 4).
- Produces: `RetentionService.setHold(UUID contactId, String reason, LocalDate setOn)`, `RetentionService.releaseHold(UUID contactId, String reason, LocalDate releasedOn)`, `RetentionService.hasActiveHold(UUID contactId) -> boolean`, `RetentionService.dueForErasure(LocalDate asOf) -> List<UUID>`; `ContactService.erase(UUID contactId, LocalDate erasedOn)` throwing `RetentionHoldActiveException`.

This is the task that carries the module's legal weight. Four behaviours, all tested: erasure deletes personal data, erasure leaves the event stream intact, erasure is refused while a hold is live, and the due-report never deletes anything by itself.

- [ ] **Step 1: Write the failing test**

```java
package pl.najem.contacts.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.contacts.ContactsEventTypes;
import pl.najem.contacts.domain.ContactErased;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RetentionServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static ContactService contacts;
    static InterestService interests;
    static ContactDirectory directory;
    static RetentionService retention;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        ContactsEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        retention = new RetentionService(store, jdbc);
        contacts = new ContactService(store, jdbc, retention);
        interests = new InterestService(store, jdbc);
        directory = new ContactDirectory(jdbc);
    }

    private static UUID aContactRetainedUntil(LocalDate retainUntil) {
        return contacts.register(new NewContact(
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), retainUntil));
    }

    @Test
    void erasureDeletesPersonalDataButLeavesTheEventStreamIntact() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        var unitId = UUID.randomUUID();
        interests.register(contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));
        var eventCountBefore = store.load(contactId).events().size();

        contacts.erase(contactId, LocalDate.of(2027, 9, 1));

        assertThat(directory.find(contactId)).isEmpty();
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where contact_id = ?", Integer.class, contactId)).isZero();
        assertThat(store.load(contactId).events()).hasSize(eventCountBefore + 1);
        assertThat(store.load(contactId).events())
            .contains(new ContactErased(contactId, LocalDate.of(2027, 9, 1)));
        assertThat(jdbc.queryForObject(
            "select erased_on from contacts_erasure_log where contact_id = ?", LocalDate.class, contactId))
            .isEqualTo(LocalDate.of(2027, 9, 1));
        assertThat(interests.forUnit(unitId)).isEmpty();
    }

    @Test
    void erasureIsRefusedWhileARetentionHoldIsActive() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() -> contacts.erase(contactId, LocalDate.of(2027, 9, 1)))
            .isInstanceOf(RetentionHoldActiveException.class)
            .hasMessageContaining("ledger-referenced");

        assertThat(directory.find(contactId)).isPresent();
    }

    @Test
    void erasureIsAllowedOnceEveryHoldIsReleased() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));
        retention.releaseHold(contactId, "ledger-referenced", LocalDate.of(2032, 1, 1));

        contacts.erase(contactId, LocalDate.of(2032, 1, 2));

        assertThat(directory.find(contactId)).isEmpty();
    }

    @Test
    void reportsContactsPastTheirRetentionDateWithoutDeletingThem() {
        var stale = aContactRetainedUntil(LocalDate.of(2026, 1, 1));
        var fresh = aContactRetainedUntil(LocalDate.of(2099, 1, 1));
        var held = aContactRetainedUntil(LocalDate.of(2026, 1, 1));
        retention.setHold(held, "ledger-referenced", LocalDate.of(2026, 1, 1));

        var due = retention.dueForErasure(LocalDate.of(2026, 8, 3));

        assertThat(due).contains(stale).doesNotContain(fresh).doesNotContain(held);
        assertThat(directory.find(stale)).isPresent();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*RetentionServiceTest*'`
Expected: FAIL — `RetentionService`, `RetentionHoldActiveException`, `ContactErased` do not exist, and `ContactService` has no three-argument constructor.

- [ ] **Step 3: Write the event records and register them**

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record RetentionHoldSet(UUID contactId, String reason, LocalDate setOn) {
}
```

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record RetentionHoldReleased(UUID contactId, String reason, LocalDate releasedOn) {
}
```

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactErased(UUID contactId, LocalDate erasedOn) {
}
```

In `ContactsEventTypes.register`, add:

```java
        registry.register(RetentionHoldSet.class);
        registry.register(RetentionHoldReleased.class);
        registry.register(ContactErased.class);
```

- [ ] **Step 4: Write the exception and the retention service**

```java
package pl.najem.contacts.application;

import java.util.List;
import java.util.UUID;

public class RetentionHoldActiveException extends RuntimeException {

    public RetentionHoldActiveException(UUID contactId, List<String> reasons) {
        super("Contact " + contactId + " cannot be erased, active retention holds: " + String.join(", ", reasons));
    }
}
```

```java
package pl.najem.contacts.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contacts.domain.RetentionHoldReleased;
import pl.najem.contacts.domain.RetentionHoldSet;
import pl.najem.eventstore.EventStore;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RetentionService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public RetentionService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public void setHold(UUID contactId, String reason, LocalDate setOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldSet(contactId, reason, setOn)), List.of());
        jdbc.update("""
            insert into contacts_retention_hold(contact_id, reason, set_on) values (?,?,?)
            on conflict (contact_id, reason) do update set set_on = excluded.set_on, released_on = null
            """, contactId, reason, setOn);
    }

    public void releaseHold(UUID contactId, String reason, LocalDate releasedOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldReleased(contactId, reason, releasedOn)), List.of());
        jdbc.update("update contacts_retention_hold set released_on = ? where contact_id = ? and reason = ?",
            releasedOn, contactId, reason);
    }

    public List<String> activeHolds(UUID contactId) {
        return jdbc.queryForList(
            "select reason from contacts_retention_hold where contact_id = ? and released_on is null order by reason",
            String.class, contactId);
    }

    public boolean hasActiveHold(UUID contactId) {
        return !activeHolds(contactId).isEmpty();
    }

    /** Reports only. Erasure stays a deliberate act — see plan decision 3 (hotspot #15 unresolved). */
    public List<UUID> dueForErasure(LocalDate asOf) {
        return jdbc.queryForList("""
            select p.contact_id from contacts_person p
            where p.retain_until is not null and p.retain_until <= ?
              and not exists (select 1 from contacts_retention_hold h
                              where h.contact_id = p.contact_id and h.released_on is null)
            order by p.retain_until
            """, UUID.class, asOf);
    }
}
```

- [ ] **Step 5: Add erasure to `ContactService`**

Change the constructor to take `RetentionService` and add the command:

```java
    private final RetentionService retention;

    public ContactService(EventStore store, JdbcTemplate jdbc, RetentionService retention) {
        this.store = store;
        this.jdbc = jdbc;
        this.retention = retention;
    }

    public void erase(UUID contactId, LocalDate erasedOn) {
        var holds = retention.activeHolds(contactId);
        if (!holds.isEmpty()) {
            throw new RetentionHoldActiveException(contactId, holds);
        }
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactErased(contactId, erasedOn)), List.of());
        jdbc.update("delete from contacts_interest where contact_id = ?", contactId);
        jdbc.update("delete from contacts_person where contact_id = ?", contactId);
        jdbc.update("insert into contacts_erasure_log(contact_id, erased_on) values (?,?)", contactId, erasedOn);
    }
```

Add the import `pl.najem.contacts.domain.ContactErased`.

- [ ] **Step 6: Fix the two earlier tests that build `ContactService`**

`ContactServiceTest` and `ContactDirectoryTest` (and `InterestServiceTest`) construct `ContactService` with two arguments. Update those `setUp` methods to build a `RetentionService` first and pass it in:

```java
        var retention = new RetentionService(store, jdbc);
        service = new ContactService(store, jdbc, retention);
```

- [ ] **Step 7: Run the module's tests to verify they pass**

Run: `./gradlew :modules:contacts:test`
Expected: PASS — all four test classes.

- [ ] **Step 8: Commit**

```bash
git add modules/contacts
git commit -m "Gate contact erasure behind the retention hold register"
```

---

### Task 6: REST adapter

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/adapter/rest/ContactsController.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/adapter/rest/InterestsController.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/adapter/rest/RetentionController.java`
- Test: covered by the e2e scenario in Task 7 (the controllers hold no logic worth a unit test — every branch they touch is already tested at the service level).

**Interfaces:**
- Consumes: every service from Tasks 2–5.
- Produces: the HTTP surface used by the e2e test and, later, the Phase 2 frontend:
  - `POST /api/contacts` → `201`, body `{"contactId": "..."}`
  - `GET /api/contacts/{id}` → `200` details, `404` when unknown or erased
  - `PUT /api/contacts/{id}/details` → `204`
  - `DELETE /api/contacts/{id}?on=YYYY-MM-DD` → `204`, or `409` when a retention hold is active
  - `POST /api/contacts/{id}/interests` → `201`, body `{"interestId": "..."}`
  - `DELETE /api/contacts/interests/{interestId}?on=YYYY-MM-DD` → `204`
  - `GET /api/contacts/units/{unitId}/interests` → `200` list
  - `POST /api/contacts/{id}/retention-holds` → `204`
  - `DELETE /api/contacts/{id}/retention-holds/{reason}?on=YYYY-MM-DD` → `204`
  - `GET /api/contacts/erasure-due?asOf=YYYY-MM-DD` → `200` list of contact ids

Wire DTOs live in this package and are separate from the application records (convention 4).

- [ ] **Step 1: Write the controllers**

```java
package pl.najem.contacts.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.contacts.application.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class ContactsController {

    public record RegisterContactRequest(String givenName, String surname, String email, String phone,
                                         String lawfulBasis, LocalDate infoClauseServedAt, LocalDate retainUntil) {
    }

    public record ContactDetailsRequest(String givenName, String surname, String email, String phone) {
    }

    private final ContactService contacts;
    private final ContactDirectory directory;

    public ContactsController(ContactService contacts, ContactDirectory directory) {
        this.contacts = contacts;
        this.directory = directory;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> register(@RequestBody RegisterContactRequest request) {
        var contactId = contacts.register(new NewContact(
            new ContactDetails(request.givenName(), request.surname(), request.email(), request.phone()),
            request.lawfulBasis(), request.infoClauseServedAt(), request.retainUntil()));
        return Map.of("contactId", contactId);
    }

    @GetMapping("/{contactId}")
    public ContactDetails find(@PathVariable UUID contactId) {
        return directory.find(contactId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping("/{contactId}/details")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correct(@PathVariable UUID contactId, @RequestBody ContactDetailsRequest request) {
        contacts.correctDetails(contactId,
            new ContactDetails(request.givenName(), request.surname(), request.email(), request.phone()),
            LocalDate.now());
    }

    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void erase(@PathVariable UUID contactId, @RequestParam(required = false) LocalDate on) {
        contacts.erase(contactId, on == null ? LocalDate.now() : on);
    }

    @ExceptionHandler(RetentionHoldActiveException.class)
    public ResponseEntity<Map<String, String>> onHold(RetentionHoldActiveException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
```

```java
package pl.najem.contacts.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import pl.najem.contacts.application.Interest;
import pl.najem.contacts.application.InterestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class InterestsController {

    public record RegisterInterestRequest(UUID unitId, BigDecimal willingToPay, LocalDate desiredStart) {
    }

    private final InterestService interests;

    public InterestsController(InterestService interests) {
        this.interests = interests;
    }

    @PostMapping("/{contactId}/interests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> register(@PathVariable UUID contactId, @RequestBody RegisterInterestRequest request) {
        return Map.of("interestId",
            interests.register(contactId, request.unitId(), request.willingToPay(), request.desiredStart()));
    }

    @DeleteMapping("/interests/{interestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable UUID interestId, @RequestParam(required = false) LocalDate on) {
        interests.withdraw(interestId, on == null ? LocalDate.now() : on);
    }

    @GetMapping("/units/{unitId}/interests")
    public List<Interest> forUnit(@PathVariable UUID unitId) {
        return interests.forUnit(unitId);
    }
}
```

```java
package pl.najem.contacts.adapter.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import pl.najem.contacts.application.RetentionService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class RetentionController {

    public record HoldRequest(String reason) {
    }

    private final RetentionService retention;

    public RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    @PostMapping("/{contactId}/retention-holds")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setHold(@PathVariable UUID contactId, @RequestBody HoldRequest request) {
        retention.setHold(contactId, request.reason(), LocalDate.now());
    }

    @DeleteMapping("/{contactId}/retention-holds/{reason}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseHold(@PathVariable UUID contactId, @PathVariable String reason,
                            @RequestParam(required = false) LocalDate on) {
        retention.releaseHold(contactId, reason, on == null ? LocalDate.now() : on);
    }

    @GetMapping("/erasure-due")
    public List<UUID> dueForErasure(@RequestParam(required = false) LocalDate asOf) {
        return retention.dueForErasure(asOf == null ? LocalDate.now() : asOf);
    }
}
```

- [ ] **Step 2: Verify the application still boots with the new beans**

Run: `./gradlew :apps:najem-app:test`
Expected: PASS — `NajemApplicationTest` context load proves the controllers and services wire up and `db/contacts` migrates inside the app.

If the `LocalDate` query parameters fail to bind, add `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` to them rather than switching to `String` parsing.

- [ ] **Step 3: Commit**

```bash
git add modules/contacts
git commit -m "Expose contacts, interests and retention over REST"
```

---

### Task 7: End-to-end lead lifecycle scenario

**Files:**
- Create: `e2e/src/test/java/pl/najem/e2e/ContactLifecycleTest.java`
- Modify: `docs/superpowers/plans/2026-08-03-phase1-contacts.md` (tick the boxes as you go)

**Interfaces:**
- Consumes: the whole REST surface from Task 6.
- Produces: nothing other modules depend on. This is roadmap rule 4 — every phase appends scenarios, never a big bang.

- [ ] **Step 1: Read the existing e2e test to copy its bootstrap exactly**

Run: `cat e2e/src/test/java/pl/najem/e2e/WalkingSkeletonTest.java`

Match its Spring Boot test annotations, Testcontainers setup, base URL construction and HTTP client. Do not invent a second style of e2e test — a reviewer should see one house pattern.

- [ ] **Step 2: Write the failing test**

The scenario is the domain doc's own walkthrough (`property-management-domain-model.md:194`), carried through to erasure. Adapt the bootstrap lines to whatever `WalkingSkeletonTest` does; the assertions below are the point of the test.

```java
package pl.najem.e2e;

// Bootstrap (annotations, container, RestClient/TestRestTemplate field) copied from WalkingSkeletonTest.

class ContactLifecycleTest {

    @Test
    void leadIsCapturedLinkedToAUnitAndErasedOnRequest() {
        // 1. Manager captures Anna from a phone call.
        var contactId = post("/api/contacts", Map.of(
            "givenName", "Anna", "surname", "Kowalska",
            "email", "anna@example.com", "phone", "+48600100200",
            "lawfulBasis", "legitimate-interest",
            "infoClauseServedAt", "2026-08-03",
            "retainUntil", "2026-08-04")).get("contactId");

        // 2. She is interested in two units.
        var unit12 = UUID.randomUUID();
        var unit14 = UUID.randomUUID();
        post("/api/contacts/" + contactId + "/interests",
            Map.of("unitId", unit12, "willingToPay", "2400", "desiredStart", "2026-10-01"));
        post("/api/contacts/" + contactId + "/interests",
            Map.of("unitId", unit14, "willingToPay", "2400", "desiredStart", "2026-10-01"));

        assertThat(getList("/api/contacts/units/" + unit12 + "/interests")).hasSize(1);

        // 3. The lead goes cold and turns up on the erasure-due report.
        assertThat(getList("/api/contacts/erasure-due?asOf=2026-08-05"))
            .contains(contactId);

        // 4. Accounting-style hold blocks erasure...
        post("/api/contacts/" + contactId + "/retention-holds", Map.of("reason", "ledger-referenced"));
        assertThat(deleteStatus("/api/contacts/" + contactId + "?on=2026-08-05"))
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(getStatus("/api/contacts/" + contactId)).isEqualTo(HttpStatus.OK);

        // 5. ...and once released, erasure removes the personal data for good.
        delete("/api/contacts/" + contactId + "/retention-holds/ledger-referenced?on=2026-08-05");
        delete("/api/contacts/" + contactId + "?on=2026-08-05");

        assertThat(getStatus("/api/contacts/" + contactId)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getList("/api/contacts/units/" + unit12 + "/interests")).isEmpty();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails, then passes**

Run: `./gradlew :e2e:test --tests '*ContactLifecycleTest*'`
Expected: FAIL first (the test class does not compile until the helpers match `WalkingSkeletonTest`'s style), then PASS once the bootstrap is right. No production code should need to change — if it does, that is a real gap and belongs back in Tasks 2–6 with its own unit test.

- [ ] **Step 4: Run the full build**

Run: `./gradlew build`
Expected: PASS, `WalkingSkeletonTest` included and untouched.

- [ ] **Step 5: Commit and post STATUS**

```bash
git add e2e docs/superpowers/plans/2026-08-03-phase1-contacts.md
git commit -m "Cover the lead lifecycle from capture to erasure end to end"
```

Then post to najem-build: `STATUS najem-contacts — DONE`, the commit hash, and the note that erasure is manual-only pending hotspot #15.

---

## Follow-ups deliberately NOT in this plan

Raise each on najem-build rather than building it unasked:

1. **`RetentionHoldSet/Released` as integration events** (accounting → contacts). Needs a joint CONTRACT-CHANGE-REQUEST with najem-accounting. Until then, holds are set over REST.
2. **`ContactErased` as an integration event**, so other modules' projections can drop cached display names. Only needed once something caches them — nothing does today.
3. **Workspace scoping.** Blocked on the human (najem-usermgmt seq 12/16). Additive follow-up migration if the answer is YES.
4. **Retention duration** (hotspot #15). `najem.contacts.lead-retention-days` is not read anywhere in Phase 1 because `retainUntil` is always supplied by the caller; wire the default in only once a domain expert gives a number.
5. **Scheduled stale-lead prompts.** The report endpoint exists; turning it into a nag (the `StatementOverdueDetected` mechanism) is Reporting's territory in Phase 2.
