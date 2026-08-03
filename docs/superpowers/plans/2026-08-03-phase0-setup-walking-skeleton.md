# Phase 0: Setup + Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A monorepo where one thin flow works end-to-end: create property → unit → tenancy → activate → rent charge posted → FakeBank statement → ingested → matched → confirmed → arrears board shows green.

**Architecture:** Gradle multi-module modular monolith. `contracts` (integration events + handler SPI) and `platform:eventstore` (jsonb event store + transactional outbox) are the only shared code. Modules (`propertymanagement`, `accounting`) are hexagonal and never depend on each other; cross-module delivery = outbox dispatcher **directly invoking handler beans (plain Java calls, no queues)**. `apps/najem-app` is the composition root; `apps/fakebank` is a separate Boot app.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Postgres 16 (docker-compose), Flyway, JdbcTemplate (no JPA), Jackson, Testcontainers, RestAssured, Awaitility, JUnit 5.

## Global Constraints

- Java 21 toolchain; Spring Boot BOM `3.3.5`; Gradle Kotlin DSL.
- Root package `pl.najem`. Module packages: `pl.najem.contracts`, `pl.najem.eventstore`, `pl.najem.pm`, `pl.najem.acc`, `pl.najem.fakebank`.
- Modules depend ONLY on `contracts` and `platform:eventstore` — never on each other.
- No PII inside events (PII lookaside decision) — skeleton events carry IDs and money only.
- Money = `BigDecimal`, dates = `LocalDate`. No floats.
- DB via JdbcTemplate; all writes inside `@Transactional` application services.
- Commits: sign as the repo user; NEVER mention any AI/LLM tool in messages; imperative mood.
- Walking-skeleton scope guard: single `rent` component, exact-reference matching, manual confirm, statuses `awaiting`/`green` only. No Keycloak, S3, deposits, media, credit notes (Phase 1).

---

### Task 1: Monorepo scaffold, Gradle multi-module, CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `.github/workflows/ci.yml`
- Create (empty dirs with `build.gradle.kts`): `contracts/`, `platform/eventstore/`, `modules/propertymanagement/`, `modules/accounting/`, `apps/najem-app/`, `apps/fakebank/`, `e2e/`

**Interfaces:** Produces the build skeleton every task compiles against.

- [ ] **Step 1: git init + scaffold**

```bash
cd /Users/jaca/Repos/NAJEM && git init
```

`settings.gradle.kts`:
```kotlin
rootProject.name = "najem"
include("contracts", "platform:eventstore", "modules:propertymanagement",
        "modules:accounting", "apps:najem-app", "apps:fakebank", "e2e")
```

Root `build.gradle.kts`:
```kotlin
plugins { java }

subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<Test> { useJUnitPlatform() }
    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.3.5"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"("org.assertj:assertj-core:3.26.3")
    }
}
```

`.gitignore`: `build/`, `.gradle/`, `.idea/`, `*.iml`.

Each subproject gets a minimal `build.gradle.kts` (`plugins { `java-library` }` for now; later tasks extend them).

- [ ] **Step 2: verify build**

Run: `./gradlew build` (generate wrapper first: `gradle wrapper --gradle-version 8.10`)
Expected: BUILD SUCCESSFUL (empty modules).

- [ ] **Step 3: CI**

`.github/workflows/ci.yml`:
```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - run: ./gradlew build
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "Scaffold Gradle multi-module monorepo with CI"
```

---

### Task 2: docker-compose Postgres + najem-app boots with Flyway

**Files:**
- Create: `docker-compose.yml`, `apps/najem-app/build.gradle.kts`, `apps/najem-app/src/main/java/pl/najem/app/NajemApplication.java`, `apps/najem-app/src/main/resources/application.yml`
- Test: `apps/najem-app/src/test/java/pl/najem/app/NajemApplicationTest.java`

**Interfaces:** Produces the composition root all modules plug into; Flyway reads locations `classpath:db/eventstore`, `classpath:db/pm`, `classpath:db/acc` (filled by later tasks).

- [ ] **Step 1: docker-compose**

```yaml
services:
  postgres:
    image: postgres:16
    environment: { POSTGRES_DB: najem, POSTGRES_USER: najem, POSTGRES_PASSWORD: najem }
    ports: ["5432:5432"]
```

- [ ] **Step 2: app skeleton (failing test first)**

`NajemApplicationTest.java`:
```java
@SpringBootTest
@Testcontainers
class NajemApplicationTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    @Test void contextLoads() {}
}
```

`apps/najem-app/build.gradle.kts`:
```kotlin
plugins { java; id("org.springframework.boot") version "3.3.5" }
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
```

`NajemApplication.java`:
```java
package pl.najem.app;
@SpringBootApplication(scanBasePackages = "pl.najem")
@EnableScheduling
public class NajemApplication {
    public static void main(String[] args) { SpringApplication.run(NajemApplication.class, args); }
}
```

`application.yml`:
```yaml
spring:
  datasource: { url: jdbc:postgresql://localhost:5432/najem, username: najem, password: najem }
  flyway: { locations: "classpath:db/eventstore,classpath:db/pm,classpath:db/acc" }
najem:
  bank: { base-url: "http://localhost:8081", iban: "PL61109010140000071219812874" }
```
(Flyway tolerates empty locations only if dirs exist — add placeholder `db/eventstore/.keep` etc. or set `spring.flyway.fail-on-missing-locations: false` until Task 4.)

- [ ] **Step 3: run test → PASS** — `./gradlew :apps:najem-app:test`

- [ ] **Step 4: Commit** — `git add -A && git commit -m "Boot composition root with Postgres and Flyway wiring"`

---

### Task 3: contracts module

**Files:**
- Create: `contracts/src/main/java/pl/najem/contracts/events/IntegrationEvent.java`, `.../TenancyActivatedEvent.java`, `.../IntegrationEventHandler.java`

**Interfaces (produces — FROZEN after this task):**
```java
public interface IntegrationEvent {}

public record TenancyActivatedEvent(UUID tenancyId, UUID unitId, LocalDate startDate,
        BigDecimal monthlyRent, String paymentReference) implements IntegrationEvent {}

public interface IntegrationEventHandler<T extends IntegrationEvent> {
    Class<T> eventType();
    void handle(T event);
}
```

- [ ] **Step 1: create the three files exactly as above** (package `pl.najem.contracts.events`; imports `java.util.UUID`, `java.time.LocalDate`, `java.math.BigDecimal`).
- [ ] **Step 2: `./gradlew :contracts:build`** → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `git commit -m "Add integration event contracts and handler SPI"`

---

### Task 4: Event store (append/load, optimistic concurrency)

**Files:**
- Create: `platform/eventstore/build.gradle.kts`, `platform/eventstore/src/main/resources/db/eventstore/V1__eventstore.sql`, `platform/eventstore/src/main/java/pl/najem/eventstore/{EventStore,StreamEvents,EventTypeRegistry,ConcurrencyException,JdbcEventStore}.java`
- Test: `platform/eventstore/src/test/java/pl/najem/eventstore/JdbcEventStoreTest.java`

**Interfaces (produces):**
```java
public interface EventStore {
    void append(UUID streamId, String streamType, long expectedVersion,
                List<Object> events, List<IntegrationEvent> integrationEvents);
    StreamEvents load(UUID streamId);
}
public record StreamEvents(long version, List<Object> events) {}   // version = last stored, 0 if empty
public class EventTypeRegistry {                                    // bean; modules register at startup
    public void register(Class<?> type);
    public Class<?> resolve(String name);   // simple class name -> class
    public String nameOf(Class<?> type);
}
public class ConcurrencyException extends RuntimeException {}
```
`build.gradle.kts` deps: `api(project(":contracts"))`, `implementation("org.springframework:spring-jdbc")`, `implementation("org.springframework:spring-context")`, `implementation("org.springframework:spring-tx")`, `implementation("com.fasterxml.jackson.core:jackson-databind")`, `implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")`; test: spring-boot-starter-test, testcontainers postgresql + junit-jupiter, flyway.

- [ ] **Step 1: migration**

```sql
create table events (
  global_seq  bigserial primary key,
  stream_id   uuid not null,
  stream_type text not null,
  version     bigint not null,
  event_type  text not null,
  payload     jsonb not null,
  occurred_at timestamptz not null default now(),
  unique (stream_id, version)
);
create table outbox (
  id           bigserial primary key,
  event_type   text not null,
  payload      jsonb not null,
  created_at   timestamptz not null default now(),
  published_at timestamptz
);
```

- [ ] **Step 2: failing tests**

```java
@Test void appendsAndLoadsInOrder() {
    var id = UUID.randomUUID();
    store.append(id, "Test", 0, List.of(new SampleEvent("a"), new SampleEvent("b")), List.of());
    var stream = store.load(id);
    assertThat(stream.version()).isEqualTo(2);
    assertThat(stream.events()).containsExactly(new SampleEvent("a"), new SampleEvent("b"));
}
@Test void rejectsStaleExpectedVersion() {
    var id = UUID.randomUUID();
    store.append(id, "Test", 0, List.of(new SampleEvent("a")), List.of());
    assertThatThrownBy(() -> store.append(id, "Test", 0, List.of(new SampleEvent("b")), List.of()))
        .isInstanceOf(ConcurrencyException.class);
}
```
(Test fixture: `record SampleEvent(String v) {}` registered in a registry; JdbcTemplate on a Testcontainers Postgres migrated by Flyway programmatically: `Flyway.configure().dataSource(...).locations("db/eventstore").load().migrate()`.)

- [ ] **Step 3: implement `JdbcEventStore`**

```java
public class JdbcEventStore implements EventStore {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper; private final EventTypeRegistry registry;
    // ctor assigns all three; mapper must have JavaTimeModule registered

    @Override public void append(UUID streamId, String streamType, long expectedVersion,
                                 List<Object> events, List<IntegrationEvent> integrationEvents) {
        long v = expectedVersion;
        try {
            for (Object e : events) {
                jdbc.update("insert into events(stream_id, stream_type, version, event_type, payload) values (?,?,?,?,?::jsonb)",
                    streamId, streamType, ++v, registry.nameOf(e.getClass()), write(e));
            }
        } catch (DuplicateKeyException ex) { throw new ConcurrencyException(); }
        for (IntegrationEvent ie : integrationEvents)
            jdbc.update("insert into outbox(event_type, payload) values (?, ?::jsonb)",
                registry.nameOf(ie.getClass()), write(ie));
    }
    @Override public StreamEvents load(UUID streamId) {
        var rows = jdbc.query("select version, event_type, payload from events where stream_id = ? order by version",
            (rs, i) -> Map.entry(rs.getLong(1), read(rs.getString(2), rs.getString(3))), streamId);
        long version = rows.isEmpty() ? 0 : rows.getLast().getKey();
        return new StreamEvents(version, rows.stream().map(Map.Entry::getValue).toList());
    }
    private String write(Object e) { /* mapper.writeValueAsString, wrap IOException in UncheckedIOException */ }
    private Object read(String type, String json) { /* mapper.readValue(json, registry.resolve(type)) */ }
}
```

- [ ] **Step 4: tests PASS** — `./gradlew :platform:eventstore:test`
- [ ] **Step 5: Commit** — `git commit -m "Add jsonb event store with optimistic concurrency and outbox table"`

---

### Task 5: Outbox dispatcher (direct in-process delivery — NO queues)

**Files:**
- Create: `platform/eventstore/src/main/java/pl/najem/eventstore/OutboxDispatcher.java`
- Test: `platform/eventstore/src/test/java/pl/najem/eventstore/OutboxDispatcherTest.java`

**Interfaces (produces):**
```java
public class OutboxDispatcher {
    public OutboxDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, EventTypeRegistry registry,
                            List<IntegrationEventHandler<?>> handlers) {}
    @Scheduled(fixedDelay = 500) public void dispatchPending(); // also callable directly (tests/e2e)
}
```

- [ ] **Step 1: failing test** — append with one `TenancyActivatedEvent` integration event; a recording `IntegrationEventHandler<TenancyActivatedEvent>` fixture; call `dispatchPending()`; assert handler received the event AND `outbox.published_at` is set AND a second `dispatchPending()` delivers nothing new.

- [ ] **Step 2: implement**

```java
public void dispatchPending() {
    var rows = jdbc.query("select id, event_type, payload from outbox where published_at is null order by id",
        (rs, i) -> new Object[]{ rs.getLong(1), rs.getString(2), rs.getString(3) });
    for (var row : rows) {
        IntegrationEvent event = (IntegrationEvent) read((String) row[1], (String) row[2]);
        var handler = handlersByType.get(event.getClass());   // Map built in ctor from handlers list
        if (handler != null) invoke(handler, event);          // plain Java method call — no broker
        jdbc.update("update outbox set published_at = now() where id = ?", row[0]);
    }
}
@SuppressWarnings("unchecked")
private <T extends IntegrationEvent> void invoke(IntegrationEventHandler<?> h, IntegrationEvent e) {
    ((IntegrationEventHandler<T>) h).handle((T) e);
}
```

- [ ] **Step 3: tests PASS** · **Step 4: Commit** — `git commit -m "Dispatch outbox events by direct handler invocation"`

---

### Task 6: PM module — Property + Unit

**Files:**
- Create: `modules/propertymanagement/build.gradle.kts` (deps: `api(project(":contracts"))`, `implementation(project(":platform:eventstore"))`, spring web/tx/jdbc via BOM), `src/main/resources/db/pm/V2__pm.sql`, `src/main/java/pl/najem/pm/domain/{PropertyCreated,UnitAdded}.java`, `src/main/java/pl/najem/pm/application/PortfolioService.java`, `src/main/java/pl/najem/pm/adapter/rest/PortfolioController.java`, `src/main/java/pl/najem/pm/PmEventTypes.java`
- Test: `modules/propertymanagement/src/test/java/pl/najem/pm/application/PortfolioServiceTest.java`

**Interfaces (produces):**
```java
record PropertyCreated(UUID propertyId, String address) {}
record UnitAdded(UUID unitId, UUID propertyId, String name, BigDecimal baseRent) {}
public class PortfolioService {
    public UUID createProperty(String address);                       // stream: propertyId, type "Property"
    public UUID addUnit(UUID propertyId, String name, BigDecimal baseRent); // stream: unitId, type "Unit"
}
// REST: POST /api/pm/properties {address} -> {propertyId}
//       POST /api/pm/properties/{propertyId}/units {name, baseRent} -> {unitId}
// PmEventTypes: @Component, ctor takes EventTypeRegistry, registers all PM event classes
```
Migration `V2__pm.sql`: skeleton projection `create table pm_unit (unit_id uuid primary key, property_id uuid not null, name text not null, base_rent numeric not null);` (service inserts row on addUnit — same tx).

- [ ] **Step 1: failing service test** (Testcontainers + Flyway locations `db/eventstore`,`db/pm`): `createProperty` then `addUnit` → `store.load(unitId).events()` contains `UnitAdded`; `pm_unit` row exists.
- [ ] **Step 2: implement service (`@Transactional`), controller, PmEventTypes.**
- [ ] **Step 3: tests PASS** — `./gradlew :modules:propertymanagement:test`
- [ ] **Step 4: wire into najem-app** — add `implementation(project(":modules:propertymanagement"))` to `apps/najem-app/build.gradle.kts`; define beans (`EventTypeRegistry`, `ObjectMapper` with JavaTimeModule, `JdbcEventStore`, `OutboxDispatcher`) in `apps/najem-app/.../PlatformConfig.java` if not yet present.
- [ ] **Step 5: Commit** — `git commit -m "Add property and unit creation in PM module"`

---

### Task 7: PM module — Tenancy reserve/activate + integration event

**Files:**
- Create: `modules/propertymanagement/src/main/java/pl/najem/pm/domain/{TenancyReserved,TenancyActivated,Tenancy}.java`, `src/main/java/pl/najem/pm/application/TenancyService.java`, `src/main/java/pl/najem/pm/adapter/rest/TenancyController.java`
- Test: `src/test/java/pl/najem/pm/domain/TenancyTest.java`, `src/test/java/pl/najem/pm/application/TenancyServiceTest.java`

**Interfaces (produces):**
```java
record TenancyReserved(UUID tenancyId, UUID unitId, LocalDate startDate,
                       BigDecimal monthlyRent, String paymentReference) {}
record TenancyActivated(UUID tenancyId, LocalDate activatedOn) {}

public class Tenancy {  // event-sourced aggregate, stream type "Tenancy"
    public static List<Object> reserve(UUID tenancyId, UUID unitId, LocalDate startDate,
                                       BigDecimal monthlyRent, String paymentReference);
    public List<Object> activate(LocalDate on);      // throws IllegalStateException unless state RESERVED
    public static Tenancy from(List<Object> events);
}
public class TenancyService {
    public UUID reserve(UUID unitId, LocalDate startDate, BigDecimal monthlyRent, String paymentReference);
    public void activate(UUID tenancyId, LocalDate on);  // appends TenancyActivated AND outbox TenancyActivatedEvent
}
// REST: POST /api/pm/tenancies {unitId,startDate,monthlyRent,paymentReference} -> {tenancyId}
//       POST /api/pm/tenancies/{id}/activate {activatedOn}
```

- [ ] **Step 1: failing domain test** — reserve→activate produces `TenancyActivated`; activating twice throws.
- [ ] **Step 2: implement `Tenancy`** (fields: id, unitId, startDate, monthlyRent, paymentReference, state enum RESERVED/ACTIVE; `from()` replays).
- [ ] **Step 3: failing service test** — after `activate`, outbox has one `TenancyActivatedEvent` row carrying the reservation's unitId/rent/reference (query outbox table directly).
- [ ] **Step 4: implement service** — `activate` loads stream, calls aggregate, then `store.append(id, "Tenancy", loaded.version(), newEvents, List.of(new TenancyActivatedEvent(...)))` using values replayed from the aggregate.
- [ ] **Step 5: tests PASS → Commit** — `git commit -m "Add tenancy reserve and activate with outbox integration event"`

---

### Task 8: Accounting — ACL handler posts rent charge, board shows awaiting

**Files:**
- Create: `modules/accounting/build.gradle.kts` (same dep shape as PM), `src/main/resources/db/acc/V3__acc.sql`, `src/main/java/pl/najem/acc/domain/ChargePosted.java`, `src/main/java/pl/najem/acc/application/LedgerService.java`, `src/main/java/pl/najem/acc/adapter/pm/TenancyActivatedHandler.java`, `src/main/java/pl/najem/acc/adapter/rest/BoardController.java`, `src/main/java/pl/najem/acc/AccEventTypes.java`
- Test: `src/test/java/pl/najem/acc/application/LedgerServiceTest.java`

**Interfaces (produces):**
```java
record ChargePosted(UUID chargeId, UUID tenancyId, String component, BigDecimal amount, LocalDate dueDate) {}
public class LedgerService {
    public UUID postRentCharge(UUID tenancyId, BigDecimal amount, LocalDate dueDate, String paymentReference);
    // appends ChargePosted to stream tenancyId type "TenancyLedger"; inserts acc_charge row; upserts acc_tenancy_status='awaiting'
}
public class TenancyActivatedHandler implements IntegrationEventHandler<TenancyActivatedEvent> {
    public Class<TenancyActivatedEvent> eventType() { return TenancyActivatedEvent.class; }
    public void handle(TenancyActivatedEvent e) { ledger.postRentCharge(e.tenancyId(), e.monthlyRent(), e.startDate(), e.paymentReference()); }
}
// REST: GET /api/acc/board -> [{tenancyId, status}]
```
`V3__acc.sql`:
```sql
create table acc_charge (charge_id uuid primary key, tenancy_id uuid not null, component text not null,
  amount numeric not null, due_date date not null, payment_reference text not null, allocated boolean not null default false);
create table acc_payment (payment_id uuid primary key, external_id text unique not null,
  amount numeric not null, title text not null, booking_date date not null, status text not null);
create table acc_suggestion (payment_id uuid primary key references acc_payment, charge_id uuid not null references acc_charge);
create table acc_tenancy_status (tenancy_id uuid primary key, status text not null);
```

- [ ] **Step 1: failing test** — `postRentCharge` → `ChargePosted` in stream, `acc_charge` row (allocated=false), board status `awaiting`.
- [ ] **Step 2: implement service + handler + controller + AccEventTypes; wire module into najem-app** (`implementation(project(":modules:accounting"))`).
- [ ] **Step 3: tests PASS → Commit** — `git commit -m "Post rent charge on tenancy activation via outbox handler"`

---

### Task 9: FakeBank app

**Files:**
- Create: `apps/fakebank/build.gradle.kts` (spring-boot plugin + starter-web), `src/main/java/pl/najem/fakebank/{FakeBankApplication,AccountsController,BankTransactionDto}.java`, `src/main/resources/application.yml` (`server.port: 8081`)
- Test: `apps/fakebank/src/test/java/pl/najem/fakebank/AccountsControllerTest.java`

**Interfaces (produces — consumed by Task 10's adapter):**
```java
public record BankTransactionDto(String id, BigDecimal amount, String title, LocalDate bookingDate) {}
// POST /api/accounts/{iban}/transactions  body: BankTransactionDto  -> 201
// GET  /api/accounts/{iban}/transactions?since=YYYY-MM-DD -> [BankTransactionDto]  (since optional)
```

- [ ] **Step 1: failing `@WebMvcTest`** — seed via POST, GET returns it; GET with `since` after bookingDate returns empty.
- [ ] **Step 2: implement** — `ConcurrentHashMap<String, List<BankTransactionDto>>` in controller; filter on `since`.
- [ ] **Step 3: tests PASS → Commit** — `git commit -m "Add FakeBank app with seed and list endpoints"`

---

### Task 10: Accounting — ingestion, exact matching, confirm → green

**Files:**
- Create: `modules/accounting/src/main/java/pl/najem/acc/domain/{PaymentIngested,PaymentAllocated}.java`, `src/main/java/pl/najem/acc/application/{BankStatementPort,BankLine,IngestionService,ReconciliationService}.java`, `src/main/java/pl/najem/acc/adapter/bank/FakeBankAdapter.java`, `src/main/java/pl/najem/acc/adapter/rest/ReconciliationController.java`
- Test: `src/test/java/pl/najem/acc/application/{IngestionServiceTest,ReconciliationServiceTest}.java`

**Interfaces (produces):**
```java
record PaymentIngested(UUID paymentId, String externalId, BigDecimal amount, String title, LocalDate bookingDate) {}
record PaymentAllocated(UUID paymentId, UUID chargeId, BigDecimal amount) {}
public record BankLine(String externalId, BigDecimal amount, String title, LocalDate bookingDate) {}
public interface BankStatementPort { List<BankLine> fetchSince(LocalDate since); }

public class IngestionService {
    public void fetchAndIngest();          // port.fetchSince(30 days back) -> ingest each
    public void ingest(BankLine line);     // dedupe by external_id; append PaymentIngested (stream paymentId, type "Payment");
}                                          // insert acc_payment status 'unmatched'; exact match: title==payment_reference
                                           // AND amount equal AND not allocated -> acc_suggestion row + status 'suggested'
public class ReconciliationService {
    public void confirm(UUID paymentId);   // reads suggestion; appends PaymentAllocated; acc_charge.allocated=true;
}                                          // acc_payment status 'allocated'; acc_tenancy_status='green'
// FakeBankAdapter implements BankStatementPort: RestClient GET {najem.bank.base-url}/api/accounts/{najem.bank.iban}/transactions?since=...
// REST: POST /api/acc/ingest/fetch (manual trigger) · GET /api/acc/suggestions -> [{paymentId,chargeId}] · POST /api/acc/payments/{id}/confirm
```

- [ ] **Step 1: failing ingestion tests** — (a) line matching an open charge (title == reference, amount equal) → suggestion + status `suggested`; (b) same externalId twice → single payment; (c) non-matching line → `unmatched`, no suggestion.
- [ ] **Step 2: implement IngestionService.**
- [ ] **Step 3: failing reconciliation test** — confirm → `PaymentAllocated` in stream, charge allocated, board `green`.
- [ ] **Step 4: implement ReconciliationService + adapter + controller; register event types in AccEventTypes.**
- [ ] **Step 5: tests PASS → Commit** — `git commit -m "Ingest bank lines with exact matching and confirm-to-allocate"`

---

### Task 11: Walking-skeleton e2e + README

**Files:**
- Create: `e2e/build.gradle.kts` (deps: spring-boot-starter-test, testcontainers postgresql, rest-assured 5.5.0, awaitility 4.2.2, project(":apps:najem-app"), project(":apps:fakebank")), `e2e/src/test/java/pl/najem/e2e/WalkingSkeletonTest.java`, `README.md`

**Interfaces:** Consumes every REST endpoint defined in Tasks 6–10 exactly as specified there.

- [ ] **Step 1: the test**

```java
@Testcontainers
class WalkingSkeletonTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    static ConfigurableApplicationContext bank, app;

    @BeforeAll static void start() {
        bank = new SpringApplicationBuilder(FakeBankApplication.class)
                .properties("server.port=0").run();
        int bankPort = bank.getEnvironment().getProperty("local.server.port", Integer.class);
        app = new SpringApplicationBuilder(NajemApplication.class).properties(
                "server.port=0",
                "spring.datasource.url=" + pg.getJdbcUrl(),
                "spring.datasource.username=" + pg.getUsername(),
                "spring.datasource.password=" + pg.getPassword(),
                "najem.bank.base-url=http://localhost:" + bankPort).run();
        RestAssured.port = app.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    @Test void tenantPaysAndBoardTurnsGreen() {
        UUID propertyId = UUID.fromString(post("/api/pm/properties", Map.of("address","Testowa 1, Kraków")).path("propertyId"));
        UUID unitId = UUID.fromString(post("/api/pm/properties/" + propertyId + "/units",
                Map.of("name","M1","baseRent","2500")).path("unitId"));
        UUID tenancyId = UUID.fromString(post("/api/pm/tenancies",
                Map.of("unitId",unitId,"startDate","2026-09-01","monthlyRent","2500",
                       "paymentReference","NAJEM/M1/2026")).path("tenancyId"));
        post("/api/pm/tenancies/" + tenancyId + "/activate", Map.of("activatedOn","2026-09-01"));

        await().until(() -> boardStatus(tenancyId).equals("awaiting"));   // outbox dispatched -> charge posted

        given().port(bankPort()).contentType(JSON)
            .body(Map.of("id","tx-1","amount","2500","title","NAJEM/M1/2026","bookingDate","2026-09-03"))
            .post("/api/accounts/PL61109010140000071219812874/transactions").then().statusCode(201);
        post("/api/acc/ingest/fetch", Map.of());

        UUID paymentId = UUID.fromString(get("/api/acc/suggestions").path("[0].paymentId"));
        post("/api/acc/payments/" + paymentId + "/confirm", Map.of());

        assertThat(boardStatus(tenancyId)).isEqualTo("green");
    }
}
```
(Helpers `post`, `get`, `boardStatus`, `bankPort` are small RestAssured wrappers in the same file.)

- [ ] **Step 2: run** — `./gradlew :e2e:test` → PASS. Fix wiring surfaced here (this test exists to catch it).
- [ ] **Step 3: README** — run instructions: `docker compose up -d`, `./gradlew :apps:fakebank:bootRun`, `./gradlew :apps:najem-app:bootRun`, curl sequence mirroring the e2e; link the domain docs and roadmap.
- [ ] **Step 4: Commit** — `git commit -m "Prove walking skeleton end to end and document run steps"`

---

## Self-Review (done at authoring)

1. **Coverage:** roadmap Phase 0 scope fully tasked (scaffold, CI, compose, contracts, event store, outbox-with-direct-dispatch, PM slice, ACL, accounting slice, FakeBank, e2e). HTMX intentionally absent — frontend is Phase 2 by roadmap.
2. **Placeholders:** none — every step carries code, exact paths, or exact commands.
3. **Type consistency verified:** `EventStore.append(UUID, String, long, List<Object>, List<IntegrationEvent>)` used identically in Tasks 4, 5, 7, 8, 10; `TenancyActivatedEvent` fields match between Tasks 3, 7, 8; REST paths in Task 11 match Tasks 6–10; `acc_*` tables in Task 8 DDL match Task 10 usage.
