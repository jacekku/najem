# Contacts Module (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `modules/contacts` — the per-workspace person registry for tenants, guarantors and leads — with the decided PII-lookaside architecture: events carry identifiers only, all personal data lives in an erasable Postgres table, and right-to-be-forgotten is a row deletion that leaves the event stream intact.

**Architecture:** Hexagonal, same shape as the landed `modules/accounting`: `domain/` holds event records, `application/` holds transactional services that append to the event store and maintain SQL projections, `adapter/rest/` exposes HTTP. Contacts is referenced by other modules **only by `ContactId`** — no cross-module Java port, no compile dependency either way (roadmap rule 2). Every table and every event is scoped by `WorkspaceId` (coordinator ruling, najem-build seq 21). Erasure is gated by a retention-hold register Contacts owns; accounting's `RetentionHoldSet/Released` will feed it via a jointly-raised contract change.

**Tech Stack:** Java 21, Spring (spring-context / spring-tx / spring-jdbc / spring-web, no Boot starters inside modules), `pl.najem.eventstore.EventStore` (jsonb + optimistic concurrency), Flyway, JUnit 5 + AssertJ + Testcontainers Postgres 16.

## Global Constraints

From the coordinator's PHASE 0 GATE (seq 15), RULING (seq 19), SUPERSESSION (seq 20), WORKSPACE RULING (seq 21) and shared-checkout rule (seq 23). These apply to every task below.

- Build env, or tests fail confusingly:
  ```bash
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  export DOCKER_HOST="unix:///Users/jaca/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
  ```
- Do **NOT** touch: the testcontainers-bom pin (1.21.3), `systemProperty api.version=1.44`, or the `-parameters` compiler flag in the root `build.gradle.kts`.
- Flyway: this module owns `classpath:db/contacts` and **version range V40–V49**. First migration is `V40__contacts.sql`. No version announcements needed inside the range.
- Do **NOT** edit `settings.gradle.kts` — `"modules:contacts"` and an empty `modules/contacts/build.gradle.kts` already landed on main. `application.yml` already lists `classpath:db/contacts`.
- **Never `git add -A` or `git add .`** (seq 23). Stage explicit paths: `git add modules/contacts docs/superpowers/plans/2026-08-03-phase1-contacts.md`. The checkout is shared with four other agents.
- Test command: `./gradlew build` (all modules incl. e2e). Fast loop: `./gradlew :modules:contacts:test`. e2e must be green on the branch before posting STATUS done.
- Branch `najem-contacts/phase1-contacts`; rebase main often; coordinator reviews and merges.
- Every new event class must be registered in `ContactsEventTypes.register()`.
- Adapters own wire DTOs — never deserialize external JSON straight into application records.
- New **integration** events (anything in `contracts/`) = CONTRACT-CHANGE-REQUEST, wait for ACK. This plan introduces **none**.
- **Workspace convention:** first field of every event is `UUID workspaceId`; every table has `workspace_id uuid not null`; every query filters on it. Cross-workspace reads do not exist in module code.
- No PII in events. Ever. A reviewer should be able to `grep` the `domain/` package and find no name, email or phone field.

## Design decisions (and why), for the reviewer

1. **What counts as PII.** `given_name`, `surname`, `email`, `phone` live only in `contacts_person` and never enter an event payload. Commercial attributes on an interest (`willing_to_pay`, `desired_start`) DO live in events, keyed by `ContactId`: once the person row is deleted, those events identify nobody. That is the pseudonymisation argument the lookaside decision rests on. If a reviewer reads B5 more strictly, the fix is to move those two fields into the projection only — a one-task change, worth settling at review rather than after.
2. **Erasure = delete the row, log the tombstone.** The `contacts_person` row is deleted outright (per B5, "right-to-be-forgotten = row deletion"). `contacts_erasure_log(workspace_id, contact_id, erased_on)` records *that* an erasure happened, holding no personal data — without it we cannot distinguish "erased" from "never existed", which matters for audit and for the erasure-due report. Erasure also drops that contact's interest rows.
3. **Retention duration is unresolved (hotspot #15, "ask domain expert").** This plan invents no legal answer and ships no default: `retainUntil` is caller-supplied, a null `retain_until` never becomes due, and the erasure-due endpoint only *reports*. A manager confirms. No scheduled auto-deletion in Phase 1. When an expert gives a number it becomes a configurable default — follow-up 4.
4. **Holds beat erasure.** Accounting's `RetentionHoldSet/Released` (~5–6y tax + civil prescription) means a ledger-referenced contact must not be erasable. Contacts owns the register and refuses erasure (HTTP 409) while any hold is unreleased. Phase 1 exposes hold set/release over REST; wiring accounting's integration events into it is a follow-up CCR to be raised jointly with najem-accounting (pre-approved in principle, seq 19 pt 6).
5. **No cross-module Java port.** PM references contact IDs (`property-management-domain-model.md:258`, Customer–Supplier). Resolving an ID to a display name is *UI composition* over REST in Phase 2, not a Java interface that would breach module isolation.
6. **Workspace scoping is the security boundary, and it is enforced by the query, not by a check.** Every read takes `(workspaceId, id)` and filters on both, so a contact belonging to another agency is not "forbidden" — it is invisible, returning 404. That fails closed. A separate `if (contact.workspaceId != caller)` check would be one forgotten call away from a leak.
7. **No uniqueness constraint on email, per workspace or otherwise.** "Is this person already known?" becomes a per-workspace question (coordinator seq 21 pt 5) — but the answer is a *lookup the manager judges*, not a database constraint. Families share an email; the same person legitimately re-enters as a new lead years later. `ContactDirectory.findByEmail(workspaceId, email)` returns the candidate matches and the manager decides. A unique index here would be a support burden, not a safeguard.
8. **Where does `workspaceId` come from before Keycloak exists?** From the `X-Workspace-Id` header, falling back to the same dev constant PM uses (`00000000-0000-0000-0000-000000000001`). Access enforcement is usermgmt + composition-root territory (seq 21 pt 8); modules just always filter. The fallback is a scaffold with an explicit deletion trigger — see follow-up 6.

## File Structure

**Modify (outside my module — the only two, both unavoidable):**
- `modules/contacts/build.gradle.kts` — the scaffold on main is `plugins { \`java-library\` }` only; add the dependency block.
- `apps/najem-app/build.gradle.kts` — add `implementation(project(":modules:contacts"))`. Without it the module is built but never wired into the app.

**Create:**
- `modules/contacts/src/main/resources/db/contacts/V40__contacts.sql` — four workspace-scoped tables.
- `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- `modules/contacts/src/main/java/pl/najem/contacts/WorkspaceContext.java` — the dev-workspace constant, in one place so it is greppable and deletable.
- `modules/contacts/src/main/java/pl/najem/contacts/domain/` — `ContactRegistered`, `ContactDetailsCorrected`, `InterestRegistered`, `InterestWithdrawn`, `RetentionHoldSet`, `RetentionHoldReleased`, `ContactErased`.
- `modules/contacts/src/main/java/pl/najem/contacts/application/` — `ContactDetails`, `NewContact`, `Interest` (value records); `ContactService`, `ContactDirectory`, `InterestService`, `RetentionService`, `RetentionHoldActiveException`.
- `modules/contacts/src/main/java/pl/najem/contacts/adapter/rest/` — `ContactsController`, `InterestsController`, `RetentionController` (wire DTOs live here).
- Tests mirroring each service under `modules/contacts/src/test/java/pl/najem/contacts/`.
- `e2e/src/test/java/pl/najem/e2e/ContactLifecycleTest.java` — appended e2e scenario (roadmap rule 4).

---

### Task 1: Module dependencies, workspace-scoped schema, event registration

**Files:**
- Modify: `modules/contacts/build.gradle.kts`
- Modify: `apps/najem-app/build.gradle.kts`
- Create: `modules/contacts/src/main/resources/db/contacts/V40__contacts.sql`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/WorkspaceContext.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/ContactRegistered.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/SchemaTest.java`

**Interfaces:**
- Consumes: `pl.najem.eventstore.EventTypeRegistry` (Phase 0, frozen).
- Produces: the `contacts_person` / `contacts_interest` / `contacts_retention_hold` / `contacts_erasure_log` tables, `WorkspaceContext.DEV_WORKSPACE_ID`, and `ContactsEventTypes.register(EventTypeRegistry)` — every later task adds its event record to that method.

- [ ] **Step 1: Write the failing test**

Two properties worth locking down on day one: PII is confined to one table, and every table is workspace-scoped.

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

    static JdbcTemplate jdbc;

    static JdbcTemplate migrated() {
        if (jdbc == null) {
            var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/eventstore", "classpath:db/contacts").load().migrate();
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @Test
    void createsTheFourContactsTables() {
        assertThat(migrated().queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public' and table_name like 'contacts%'
            order by table_name
            """, String.class))
            .containsExactly("contacts_erasure_log", "contacts_interest",
                "contacts_person", "contacts_retention_hold");
    }

    @Test
    void confinesPersonalDataToThePersonTable() {
        assertThat(migrated().queryForList("""
            select table_name || '.' || column_name from information_schema.columns
            where table_schema = 'public' and table_name like 'contacts%'
              and column_name in ('given_name', 'surname', 'email', 'phone')
            """, String.class))
            .isNotEmpty()
            .allSatisfy(column -> assertThat(column).startsWith("contacts_person."));
    }

    @Test
    void scopesEveryContactsTableByWorkspace() {
        assertThat(migrated().queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'public' and table_name like 'contacts%'
              and table_name not in (select table_name from information_schema.columns
                                     where table_schema = 'public' and column_name = 'workspace_id')
            """, String.class))
            .isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*SchemaTest*'`
Expected: FAIL — the module has no dependencies yet, so the test does not compile (`Flyway`, `PostgreSQLContainer` unresolved).

- [ ] **Step 3: Fill in the build files**

`modules/contacts/build.gradle.kts` — replace the scaffold's contents, mirroring `modules/accounting/build.gradle.kts`:

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

`apps/najem-app/build.gradle.kts` — add below the accounting line:

```kotlin
    implementation(project(":modules:contacts"))
```

- [ ] **Step 4: Write the migration**

`modules/contacts/src/main/resources/db/contacts/V40__contacts.sql`:

```sql
-- PII lookaside: the ONLY table in the system holding personal data.
-- Right-to-be-forgotten = delete the row here; event streams stay untouched.
-- Workspace (= agency) is the hard tenancy boundary: every read filters on it.
create table contacts_person (
  contact_id            uuid primary key,
  workspace_id          uuid not null,
  given_name            text not null,
  surname               text not null,
  email                 text,
  phone                 text,
  lawful_basis          text not null,
  info_clause_served_at date,
  retain_until          date
);

-- Deliberately NOT unique: families share an email, and the same person may
-- legitimately re-enter as a new lead. This index serves the "do we already
-- know this person?" lookup, which a manager judges. See plan decision 7.
create index contacts_person_by_email on contacts_person (workspace_id, email);
create index contacts_person_by_retention on contacts_person (workspace_id, retain_until);

-- Tombstone: records THAT an erasure happened. Holds no personal data.
create table contacts_erasure_log (
  contact_id   uuid primary key,
  workspace_id uuid not null,
  erased_on    date not null
);

-- Lead = contact + interest link to unit(s). Deliberately thin, no CRM.
create table contacts_interest (
  interest_id    uuid primary key,
  workspace_id   uuid not null,
  contact_id     uuid not null,
  unit_id        uuid not null,
  willing_to_pay numeric,
  desired_start  date,
  status         text not null default 'active'
);

create index contacts_interest_by_unit on contacts_interest (workspace_id, unit_id);
create index contacts_interest_by_contact on contacts_interest (workspace_id, contact_id);

-- Erasure gate. Accounting's ~5-6y tax/civil-prescription holds land here.
create table contacts_retention_hold (
  contact_id   uuid not null,
  workspace_id uuid not null,
  reason       text not null,
  set_on       date not null,
  released_on  date,
  primary key (contact_id, reason)
);
```

- [ ] **Step 5: Write the workspace constant, the first event record and the registry**

`WorkspaceContext.java` — one home for the pre-Keycloak fallback so it is greppable and deletable:

```java
package pl.najem.contacts;

import java.util.UUID;

/**
 * Scaffold until UserManagement lands Keycloak-backed workspace claims.
 * Mirrors pm's TenancyService.DEV_WORKSPACE_ID. Delete both together.
 */
public final class WorkspaceContext {

    public static final UUID DEV_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WorkspaceContext() {
    }
}
```

`domain/ContactRegistered.java` — workspaceId first (coordinator convention), and no name, email or phone anywhere:

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactRegistered(UUID workspaceId, UUID contactId, String lawfulBasis,
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
Expected: PASS (three tests)

- [ ] **Step 7: Verify the whole build is still green**

Run: `./gradlew build`
Expected: PASS, including `e2e:test` (WalkingSkeletonTest). Wiring a new module into the app must not disturb the skeleton.

- [ ] **Step 8: Commit**

```bash
git add modules/contacts apps/najem-app/build.gradle.kts
git commit -m "Add workspace scoped contacts schema and module wiring"
```

---

### Task 2: Register a contact (PII into the lookaside, IDs into the event)

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactDetails.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/NewContact.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactServiceTest.java`

**Interfaces:**
- Consumes: `EventStore.load(UUID)` / `EventStore.append(UUID streamId, String type, long expectedVersion, List<Object> events, List<Object> integrationEvents)`; `ContactsEventTypes.register` and `WorkspaceContext.DEV_WORKSPACE_ID` (Task 1).
- Produces: `ContactDetails(String givenName, String surname, String email, String phone)`; `NewContact(UUID workspaceId, ContactDetails details, String lawfulBasis, LocalDate infoClauseServedAt, LocalDate retainUntil)`; `ContactService.register(NewContact) -> UUID`. Stream type is `"Contact"`, stream id is the contact id — every later task appends to that same stream.

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
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ContactServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static final UUID AGENCY = UUID.randomUUID();

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
        var anna = new NewContact(AGENCY,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), LocalDate.of(2027, 8, 3));

        var contactId = service.register(anna);

        assertThat(store.load(contactId).events()).containsExactly(
            new ContactRegistered(AGENCY, contactId, "legitimate-interest",
                LocalDate.of(2026, 8, 3), LocalDate.of(2027, 8, 3)));
        assertThat(jdbc.queryForMap("select * from contacts_person where contact_id = ?", contactId))
            .containsEntry("workspace_id", AGENCY)
            .containsEntry("given_name", "Anna")
            .containsEntry("surname", "Kowalska")
            .containsEntry("email", "anna@example.com")
            .containsEntry("phone", "+48600100200");
    }

    @Test
    void keepsPersonalDataOutOfTheEventStorePayload() {
        var contactId = service.register(new NewContact(AGENCY,
            new ContactDetails("Piotr", "Nowak", "piotr@example.com", "+48600300400"),
            "contract", LocalDate.of(2026, 8, 3), null));

        var payloads = jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, contactId);

        assertThat(payloads).isNotEmpty();
        assertThat(payloads).noneSatisfy(payload ->
            assertThat(payload).containsAnyOf("Piotr", "Nowak", "piotr@example.com", "+48600300400"));
    }
}
```

> If the event-store table or payload column is not named `events` / `payload`, read `platform/eventstore/src/main/resources/db/eventstore/V1__eventstore.sql` and use the real names. Do not change the event store — it is frozen.

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
import java.util.UUID;

public record NewContact(UUID workspaceId, ContactDetails details, String lawfulBasis,
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
            List.of(new ContactRegistered(contact.workspaceId(), contactId, contact.lawfulBasis(),
                contact.infoClauseServedAt(), contact.retainUntil())), List.of());
        jdbc.update("""
            insert into contacts_person(contact_id, workspace_id, given_name, surname, email, phone,
                                        lawful_basis, info_clause_served_at, retain_until)
            values (?,?,?,?,?,?,?,?,?)
            """,
            contactId, contact.workspaceId(), contact.details().givenName(), contact.details().surname(),
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

### Task 3: Resolve, look up and correct contact details

**Files:**
- Create: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactDirectory.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/application/ContactService.java`
- Modify: `modules/contacts/src/main/java/pl/najem/contacts/ContactsEventTypes.java`
- Create: `modules/contacts/src/main/java/pl/najem/contacts/domain/ContactDetailsCorrected.java`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/ContactDirectoryTest.java`

**Interfaces:**
- Consumes: `ContactService.register` (Task 2).
- Produces: `ContactDirectory.find(UUID workspaceId, UUID contactId) -> Optional<ContactDetails>`; `ContactDirectory.findByEmail(UUID workspaceId, String email) -> List<UUID>`; `ContactService.correctDetails(UUID workspaceId, UUID contactId, ContactDetails details, LocalDate correctedOn)`.

The cross-workspace test below is the one that matters most: it proves the boundary fails closed.

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

    static final UUID AGENCY = UUID.randomUUID();
    static final UUID OTHER_AGENCY = UUID.randomUUID();

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

    private static UUID anna(UUID workspaceId) {
        return service.register(new NewContact(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));
    }

    @Test
    void resolvesRegisteredContactById() {
        var contactId = anna(AGENCY);

        assertThat(directory.find(AGENCY, contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"));
    }

    @Test
    void returnsEmptyForUnknownContact() {
        assertThat(directory.find(AGENCY, UUID.randomUUID())).isEmpty();
    }

    @Test
    void doesNotResolveAContactBelongingToAnotherWorkspace() {
        var contactId = anna(OTHER_AGENCY);

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    @Test
    void findsCandidatesByEmailWithinTheWorkspaceOnly() {
        var mine = anna(AGENCY);
        anna(OTHER_AGENCY);

        assertThat(directory.findByEmail(AGENCY, "anna@example.com")).containsExactly(mine);
    }

    @Test
    void correctsDetailsWithoutLeakingThemIntoTheEvent() {
        var contactId = service.register(new NewContact(AGENCY,
            new ContactDetails("Ana", "Kowalsk", "ana@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));

        service.correctDetails(AGENCY, contactId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100201"),
            LocalDate.of(2026, 8, 4));

        assertThat(directory.find(AGENCY, contactId))
            .contains(new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100201"));
        assertThat(store.load(contactId).events())
            .contains(new ContactDetailsCorrected(AGENCY, contactId, LocalDate.of(2026, 8, 4)));
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

public record ContactDetailsCorrected(UUID workspaceId, UUID contactId, LocalDate correctedOn) {
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContactDirectory {

    private final JdbcTemplate jdbc;

    public ContactDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ContactDetails> find(UUID workspaceId, UUID contactId) {
        return jdbc.query("""
                select given_name, surname, email, phone from contacts_person
                where workspace_id = ? and contact_id = ?
                """,
                (rs, i) -> new ContactDetails(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                workspaceId, contactId)
            .stream().findFirst();
    }

    /** Candidates for "do we already know this person?" — the manager judges. See plan decision 7. */
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return jdbc.queryForList("""
            select contact_id from contacts_person
            where workspace_id = ? and email = ? order by contact_id
            """, UUID.class, workspaceId, email);
    }
}
```

- [ ] **Step 5: Add the correction command to `ContactService`**

```java
    public void correctDetails(UUID workspaceId, UUID contactId, ContactDetails details, LocalDate correctedOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactDetailsCorrected(workspaceId, contactId, correctedOn)), List.of());
        jdbc.update("""
            update contacts_person set given_name = ?, surname = ?, email = ?, phone = ?
            where workspace_id = ? and contact_id = ?
            """,
            details.givenName(), details.surname(), details.email(), details.phone(), workspaceId, contactId);
    }
```

Add the imports `pl.najem.contacts.domain.ContactDetailsCorrected` and `java.time.LocalDate`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :modules:contacts:test --tests '*ContactDirectoryTest*'`
Expected: PASS (five tests)

- [ ] **Step 7: Commit**

```bash
git add modules/contacts
git commit -m "Resolve and correct contact details within a workspace"
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
- Produces: `Interest(UUID interestId, UUID contactId, UUID unitId, BigDecimal willingToPay, LocalDate desiredStart, String status)` — no `workspaceId` field, because every query is already workspace-filtered and repeating it in the row would invite someone to trust the field instead of the filter; `InterestService.register(UUID workspaceId, UUID contactId, UUID unitId, BigDecimal willingToPay, LocalDate desiredStart) -> UUID`; `InterestService.withdraw(UUID workspaceId, UUID interestId, LocalDate withdrawnOn)`; `InterestService.forUnit(UUID workspaceId, UUID unitId) -> List<Interest>`; `InterestService.eventsFor(UUID contactId) -> List<Object>`.

A contact may be interested in many units (`property-management-domain-model.md:253`). One interest per (contact, unit) is NOT enforced — re-registering after a withdrawal is normal, and the domain doc asks for thinness, not uniqueness.

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

    static final UUID AGENCY = UUID.randomUUID();
    static final UUID OTHER_AGENCY = UUID.randomUUID();

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

    private static UUID aContactIn(UUID workspaceId) {
        return contacts.register(new NewContact(workspaceId,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), null));
    }

    @Test
    void registersInterestInSeveralUnitsForOneContact() {
        var contactId = aContactIn(AGENCY);
        var unit12 = UUID.randomUUID();
        var unit14 = UUID.randomUUID();

        var first = interests.register(AGENCY, contactId, unit12, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));
        interests.register(AGENCY, contactId, unit14, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.forUnit(AGENCY, unit12))
            .extracting(Interest::interestId, Interest::contactId, Interest::unitId, Interest::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(first, contactId, unit12, "active"));
        assertThat(interests.forUnit(AGENCY, unit14)).hasSize(1);
    }

    @Test
    void withdrawnInterestDropsOutOfTheUnitView() {
        var contactId = aContactIn(AGENCY);
        var unitId = UUID.randomUUID();
        var interestId = interests.register(AGENCY, contactId, unitId,
            new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        interests.withdraw(AGENCY, interestId, LocalDate.of(2026, 9, 1));

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void doesNotShowInterestsFromAnotherWorkspace() {
        var contactId = aContactIn(OTHER_AGENCY);
        var unitId = UUID.randomUUID();
        interests.register(OTHER_AGENCY, contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void recordsInterestOnTheContactStream() {
        var contactId = aContactIn(AGENCY);
        var unitId = UUID.randomUUID();

        var interestId = interests.register(AGENCY, contactId, unitId,
            new BigDecimal("2400"), LocalDate.of(2026, 10, 1));

        assertThat(interests.eventsFor(contactId)).contains(
            new InterestRegistered(AGENCY, interestId, contactId, unitId,
                new BigDecimal("2400"), LocalDate.of(2026, 10, 1)));
    }
}
```

> The first test compares fields rather than whole `Interest` values on purpose: a `numeric` column round-trips `2400` as `2400.00`, and `BigDecimal.equals` is scale-sensitive. Comparing the identity fields keeps the assertion honest without asserting on a scale nobody cares about.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :modules:contacts:test --tests '*InterestServiceTest*'`
Expected: FAIL — `InterestService`, `Interest`, `InterestRegistered` do not exist.

- [ ] **Step 3: Write the event records and register them**

```java
package pl.najem.contacts.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InterestRegistered(UUID workspaceId, UUID interestId, UUID contactId, UUID unitId,
                                 BigDecimal willingToPay, LocalDate desiredStart) {
}
```

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record InterestWithdrawn(UUID workspaceId, UUID interestId, UUID contactId, LocalDate withdrawnOn) {
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

    public UUID register(UUID workspaceId, UUID contactId, UUID unitId,
                         BigDecimal willingToPay, LocalDate desiredStart) {
        UUID interestId = UUID.randomUUID();
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestRegistered(workspaceId, interestId, contactId, unitId,
                willingToPay, desiredStart)), List.of());
        jdbc.update("""
            insert into contacts_interest(interest_id, workspace_id, contact_id, unit_id,
                                          willing_to_pay, desired_start, status)
            values (?,?,?,?,?,?, 'active')
            """, interestId, workspaceId, contactId, unitId, willingToPay, desiredStart);
        return interestId;
    }

    public void withdraw(UUID workspaceId, UUID interestId, LocalDate withdrawnOn) {
        UUID contactId = jdbc.queryForObject(
            "select contact_id from contacts_interest where workspace_id = ? and interest_id = ?",
            UUID.class, workspaceId, interestId);
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new InterestWithdrawn(workspaceId, interestId, contactId, withdrawnOn)), List.of());
        jdbc.update("update contacts_interest set status = 'withdrawn' where workspace_id = ? and interest_id = ?",
            workspaceId, interestId);
    }

    public List<Interest> forUnit(UUID workspaceId, UUID unitId) {
        return jdbc.query("""
            select interest_id, contact_id, unit_id, willing_to_pay, desired_start, status
            from contacts_interest
            where workspace_id = ? and unit_id = ? and status = 'active' order by interest_id
            """,
            (rs, i) -> new Interest(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBigDecimal(4),
                rs.getObject(5, LocalDate.class), rs.getString(6)),
            workspaceId, unitId);
    }

    public List<Object> eventsFor(UUID contactId) {
        return List.copyOf(store.load(contactId).events());
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :modules:contacts:test --tests '*InterestServiceTest*'`
Expected: PASS (four tests)

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
- Modify: the `setUp` of `ContactServiceTest`, `ContactDirectoryTest`, `InterestServiceTest`
- Test: `modules/contacts/src/test/java/pl/najem/contacts/application/RetentionServiceTest.java`

**Interfaces:**
- Consumes: `ContactService.register` (Task 2), `ContactDirectory.find` (Task 3), `InterestService.register` (Task 4).
- Produces: `RetentionService.setHold(UUID workspaceId, UUID contactId, String reason, LocalDate setOn)`, `RetentionService.releaseHold(UUID workspaceId, UUID contactId, String reason, LocalDate releasedOn)`, `RetentionService.activeHolds(UUID workspaceId, UUID contactId) -> List<String>`, `RetentionService.dueForErasure(UUID workspaceId, LocalDate asOf) -> List<UUID>`; `ContactService.erase(UUID workspaceId, UUID contactId, LocalDate erasedOn)` throwing `RetentionHoldActiveException`.

This is the task carrying the module's legal weight. Four behaviours, all tested: erasure deletes personal data, erasure leaves the event stream intact, erasure is refused while a hold is live, and the due-report never deletes anything itself.

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

    static final UUID AGENCY = UUID.randomUUID();
    static final UUID OTHER_AGENCY = UUID.randomUUID();

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
        return contacts.register(new NewContact(AGENCY,
            new ContactDetails("Anna", "Kowalska", "anna@example.com", "+48600100200"),
            "legitimate-interest", LocalDate.of(2026, 8, 3), retainUntil));
    }

    @Test
    void erasureDeletesPersonalDataButLeavesTheEventStreamIntact() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        var unitId = UUID.randomUUID();
        interests.register(AGENCY, contactId, unitId, new BigDecimal("2400"), LocalDate.of(2026, 10, 1));
        var eventCountBefore = store.load(contactId).events().size();

        contacts.erase(AGENCY, contactId, LocalDate.of(2027, 9, 1));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
        assertThat(jdbc.queryForObject(
            "select count(*) from contacts_person where contact_id = ?", Integer.class, contactId)).isZero();
        assertThat(store.load(contactId).events()).hasSize(eventCountBefore + 1);
        assertThat(store.load(contactId).events())
            .contains(new ContactErased(AGENCY, contactId, LocalDate.of(2027, 9, 1)));
        assertThat(jdbc.queryForObject(
            "select erased_on from contacts_erasure_log where contact_id = ?", LocalDate.class, contactId))
            .isEqualTo(LocalDate.of(2027, 9, 1));
        assertThat(interests.forUnit(AGENCY, unitId)).isEmpty();
    }

    @Test
    void erasureIsRefusedWhileARetentionHoldIsActive() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));

        assertThatThrownBy(() -> contacts.erase(AGENCY, contactId, LocalDate.of(2027, 9, 1)))
            .isInstanceOf(RetentionHoldActiveException.class)
            .hasMessageContaining("ledger-referenced");

        assertThat(directory.find(AGENCY, contactId)).isPresent();
    }

    @Test
    void erasureIsAllowedOnceEveryHoldIsReleased() {
        var contactId = aContactRetainedUntil(LocalDate.of(2027, 8, 3));
        retention.setHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2026, 8, 3));
        retention.releaseHold(AGENCY, contactId, "ledger-referenced", LocalDate.of(2032, 1, 1));

        contacts.erase(AGENCY, contactId, LocalDate.of(2032, 1, 2));

        assertThat(directory.find(AGENCY, contactId)).isEmpty();
    }

    @Test
    void reportsContactsPastTheirRetentionDateWithoutDeletingThem() {
        var stale = aContactRetainedUntil(LocalDate.of(2026, 1, 1));
        var fresh = aContactRetainedUntil(LocalDate.of(2099, 1, 1));
        var neverDue = aContactRetainedUntil(null);
        var held = aContactRetainedUntil(LocalDate.of(2026, 1, 1));
        retention.setHold(AGENCY, held, "ledger-referenced", LocalDate.of(2026, 1, 1));

        var due = retention.dueForErasure(AGENCY, LocalDate.of(2026, 8, 3));

        assertThat(due).contains(stale).doesNotContain(fresh, neverDue, held);
        assertThat(directory.find(AGENCY, stale)).isPresent();
    }

    @Test
    void doesNotReportContactsFromAnotherWorkspace() {
        var stale = aContactRetainedUntil(LocalDate.of(2026, 1, 1));

        assertThat(retention.dueForErasure(OTHER_AGENCY, LocalDate.of(2026, 8, 3))).doesNotContain(stale);
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

public record RetentionHoldSet(UUID workspaceId, UUID contactId, String reason, LocalDate setOn) {
}
```

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record RetentionHoldReleased(UUID workspaceId, UUID contactId, String reason, LocalDate releasedOn) {
}
```

```java
package pl.najem.contacts.domain;

import java.time.LocalDate;
import java.util.UUID;

public record ContactErased(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
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

    public void setHold(UUID workspaceId, UUID contactId, String reason, LocalDate setOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldSet(workspaceId, contactId, reason, setOn)), List.of());
        jdbc.update("""
            insert into contacts_retention_hold(contact_id, workspace_id, reason, set_on) values (?,?,?,?)
            on conflict (contact_id, reason) do update set set_on = excluded.set_on, released_on = null
            """, contactId, workspaceId, reason, setOn);
    }

    public void releaseHold(UUID workspaceId, UUID contactId, String reason, LocalDate releasedOn) {
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new RetentionHoldReleased(workspaceId, contactId, reason, releasedOn)), List.of());
        jdbc.update("""
            update contacts_retention_hold set released_on = ?
            where workspace_id = ? and contact_id = ? and reason = ?
            """, releasedOn, workspaceId, contactId, reason);
    }

    public List<String> activeHolds(UUID workspaceId, UUID contactId) {
        return jdbc.queryForList("""
            select reason from contacts_retention_hold
            where workspace_id = ? and contact_id = ? and released_on is null order by reason
            """, String.class, workspaceId, contactId);
    }

    public boolean hasActiveHold(UUID workspaceId, UUID contactId) {
        return !activeHolds(workspaceId, contactId).isEmpty();
    }

    /** Reports only. Erasure stays a deliberate act — see plan decision 3 (hotspot #15 unresolved). */
    public List<UUID> dueForErasure(UUID workspaceId, LocalDate asOf) {
        return jdbc.queryForList("""
            select p.contact_id from contacts_person p
            where p.workspace_id = ? and p.retain_until is not null and p.retain_until <= ?
              and not exists (select 1 from contacts_retention_hold h
                              where h.contact_id = p.contact_id and h.released_on is null)
            order by p.retain_until
            """, UUID.class, workspaceId, asOf);
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

    public void erase(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
        var holds = retention.activeHolds(workspaceId, contactId);
        if (!holds.isEmpty()) {
            throw new RetentionHoldActiveException(contactId, holds);
        }
        var stream = store.load(contactId);
        store.append(contactId, "Contact", stream.version(),
            List.of(new ContactErased(workspaceId, contactId, erasedOn)), List.of());
        jdbc.update("delete from contacts_interest where workspace_id = ? and contact_id = ?",
            workspaceId, contactId);
        jdbc.update("delete from contacts_person where workspace_id = ? and contact_id = ?",
            workspaceId, contactId);
        jdbc.update("insert into contacts_erasure_log(contact_id, workspace_id, erased_on) values (?,?,?)",
            contactId, workspaceId, erasedOn);
    }
```

Add the import `pl.najem.contacts.domain.ContactErased`.

- [ ] **Step 6: Fix the earlier tests that build `ContactService`**

`ContactServiceTest`, `ContactDirectoryTest` and `InterestServiceTest` construct `ContactService` with two arguments. Update each `setUp` to build a `RetentionService` first:

```java
        var retention = new RetentionService(store, jdbc);
        service = new ContactService(store, jdbc, retention);
```

- [ ] **Step 7: Run the module's tests to verify they pass**

Run: `./gradlew :modules:contacts:test`
Expected: PASS — all five test classes.

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
- Test: covered by the e2e scenario in Task 7. The controllers hold no branching logic of their own — every path they touch is already tested at the service level.

**Interfaces:**
- Consumes: every service from Tasks 2–5.
- Produces: the HTTP surface used by the e2e test and, later, the Phase 2 frontend. Workspace comes from the optional `X-Workspace-Id` header, defaulting to `WorkspaceContext.DEV_WORKSPACE_ID` (plan decision 8):
  - `POST /api/contacts` → `201`, body `{"contactId": "..."}`
  - `GET /api/contacts/{id}` → `200` details, `404` when unknown, erased, or in another workspace
  - `GET /api/contacts?email=...` → `200` list of candidate contact ids
  - `PUT /api/contacts/{id}/details` → `204`
  - `DELETE /api/contacts/{id}?on=YYYY-MM-DD` → `204`, or `409` when a retention hold is active
  - `POST /api/contacts/{id}/interests` → `201`, body `{"interestId": "..."}`
  - `DELETE /api/contacts/interests/{interestId}?on=YYYY-MM-DD` → `204`
  - `GET /api/contacts/units/{unitId}/interests` → `200` list
  - `POST /api/contacts/{id}/retention-holds` → `204`
  - `DELETE /api/contacts/{id}/retention-holds/{reason}?on=YYYY-MM-DD` → `204`
  - `GET /api/contacts/erasure-due?asOf=YYYY-MM-DD` → `200` list of contact ids

Wire DTOs live in this package, separate from the application records (convention 4).

- [ ] **Step 1: Write the controllers**

```java
package pl.najem.contacts.adapter.rest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.contacts.WorkspaceContext;
import pl.najem.contacts.application.*;

import java.time.LocalDate;
import java.util.List;
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
    public Map<String, UUID> register(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                                      @RequestBody RegisterContactRequest request) {
        var contactId = contacts.register(new NewContact(workspace(workspaceId),
            new ContactDetails(request.givenName(), request.surname(), request.email(), request.phone()),
            request.lawfulBasis(), request.infoClauseServedAt(), request.retainUntil()));
        return Map.of("contactId", contactId);
    }

    @GetMapping("/{contactId}")
    public ContactDetails find(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                               @PathVariable UUID contactId) {
        return directory.find(workspace(workspaceId), contactId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping(params = "email")
    public List<UUID> findByEmail(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                                  @RequestParam String email) {
        return directory.findByEmail(workspace(workspaceId), email);
    }

    @PutMapping("/{contactId}/details")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correct(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                        @PathVariable UUID contactId, @RequestBody ContactDetailsRequest request) {
        contacts.correctDetails(workspace(workspaceId), contactId,
            new ContactDetails(request.givenName(), request.surname(), request.email(), request.phone()),
            LocalDate.now());
    }

    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void erase(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                      @PathVariable UUID contactId,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        contacts.erase(workspace(workspaceId), contactId, on == null ? LocalDate.now() : on);
    }

    @ExceptionHandler(RetentionHoldActiveException.class)
    public ResponseEntity<Map<String, String>> onHold(RetentionHoldActiveException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    static UUID workspace(UUID fromHeader) {
        return fromHeader == null ? WorkspaceContext.DEV_WORKSPACE_ID : fromHeader;
    }
}
```

```java
package pl.najem.contacts.adapter.rest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import pl.najem.contacts.application.Interest;
import pl.najem.contacts.application.InterestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static pl.najem.contacts.adapter.rest.ContactsController.workspace;

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
    public Map<String, UUID> register(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                                      @PathVariable UUID contactId,
                                      @RequestBody RegisterInterestRequest request) {
        return Map.of("interestId", interests.register(workspace(workspaceId), contactId,
            request.unitId(), request.willingToPay(), request.desiredStart()));
    }

    @DeleteMapping("/interests/{interestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                         @PathVariable UUID interestId,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        interests.withdraw(workspace(workspaceId), interestId, on == null ? LocalDate.now() : on);
    }

    @GetMapping("/units/{unitId}/interests")
    public List<Interest> forUnit(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                                  @PathVariable UUID unitId) {
        return interests.forUnit(workspace(workspaceId), unitId);
    }
}
```

```java
package pl.najem.contacts.adapter.rest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import pl.najem.contacts.application.RetentionService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static pl.najem.contacts.adapter.rest.ContactsController.workspace;

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
    public void setHold(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                        @PathVariable UUID contactId, @RequestBody HoldRequest request) {
        retention.setHold(workspace(workspaceId), contactId, request.reason(), LocalDate.now());
    }

    @DeleteMapping("/{contactId}/retention-holds/{reason}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releaseHold(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                            @PathVariable UUID contactId, @PathVariable String reason,
                            @RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        retention.releaseHold(workspace(workspaceId), contactId, reason, on == null ? LocalDate.now() : on);
    }

    @GetMapping("/erasure-due")
    public List<UUID> dueForErasure(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return retention.dueForErasure(workspace(workspaceId), asOf == null ? LocalDate.now() : asOf);
    }
}
```

- [ ] **Step 2: Verify the application still boots with the new beans**

Run: `./gradlew :apps:najem-app:test`
Expected: PASS — `NajemApplicationTest`'s context load proves the controllers and services wire up and `db/contacts` migrates inside the app.

Watch for a mapping clash between `GET /api/contacts?email=` and `GET /api/contacts/erasure-due` — they do not collide (different paths), but if Spring complains about ambiguous handlers, move the email lookup to `/api/contacts/search` rather than loosening the `params` condition.

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
- Consumes: the REST surface from Task 6.
- Produces: nothing other modules depend on. This is roadmap rule 4 — every phase appends scenarios, never a big bang.

- [ ] **Step 1: Read the existing e2e test to copy its bootstrap exactly**

Run: `cat e2e/src/test/java/pl/najem/e2e/WalkingSkeletonTest.java`

Match its Spring Boot test annotations, Testcontainers setup, base URL construction and HTTP client. Do not invent a second style of e2e test — a reviewer should see one house pattern.

- [ ] **Step 2: Write the failing test**

The scenario is the domain doc's own walkthrough (`property-management-domain-model.md:194`), carried through to erasure. Adapt the request helpers to whatever `WalkingSkeletonTest` uses; the assertions are the point.

```java
package pl.najem.e2e;

// Bootstrap (annotations, container, RestClient/TestRestTemplate field) copied from WalkingSkeletonTest.
// No X-Workspace-Id header is sent: the dev-workspace default is exercised, which is what the app
// runs on until UserManagement lands Keycloak claims.

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
        assertThat(getList("/api/contacts/erasure-due?asOf=2026-08-05")).contains(contactId);

        // 4. An accounting-style hold blocks erasure...
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
Expected: FAIL first (the helpers do not compile until they match `WalkingSkeletonTest`'s style), then PASS once the bootstrap is right. No production code should need to change — if it does, that is a real gap and belongs back in Tasks 2–6 with its own unit test.

- [ ] **Step 4: Run the full build**

Run: `./gradlew build`
Expected: PASS, `WalkingSkeletonTest` included and untouched.

- [ ] **Step 5: Commit and post STATUS**

```bash
git add modules/contacts e2e docs/superpowers/plans/2026-08-03-phase1-contacts.md
git commit -m "Cover the lead lifecycle from capture to erasure end to end"
```

Then post to najem-build: `STATUS najem-contacts — DONE`, the commit hash, and the note that erasure is manual-only pending hotspot #15.

---

## Follow-ups deliberately NOT in this plan

Raise each on najem-build rather than building it unasked:

1. **`RetentionHoldSet/Released` as integration events** (accounting → contacts). Joint CONTRACT-CHANGE-REQUEST with najem-accounting, pre-approved in principle (seq 19 pt 6) — submit exact record shapes. Until then, holds are set over REST.
2. **`ContactErased` as an integration event**, so other modules' projections can drop cached display names. Only needed once something caches them — nothing does today.
3. **Workspace existence validation.** Once najem-usermgmt lands `WorkspaceCreated` as an integration event, Contacts can reject writes for unknown workspaces. Today any UUID is accepted; the boundary still isolates correctly, it just does not validate.
4. **Retention duration** (hotspot #15). Becomes a configurable default for `retainUntil` once a domain expert gives a number.
5. **Scheduled stale-lead prompts.** The report endpoint exists; turning it into a nag (the `StatementOverdueDetected` mechanism) is Reporting's territory in Phase 2.
6. **Delete `WorkspaceContext.DEV_WORKSPACE_ID`** when Keycloak claims supply the workspace — same moment pm deletes `TenancyService.DEV_WORKSPACE_ID`. Grep for both together.
