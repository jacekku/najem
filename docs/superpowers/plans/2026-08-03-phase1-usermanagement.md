# Phase 1: UserManagement (Keycloak + Workspaces) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** NAJEM gets workspaces as a hard multi-tenancy boundary (workspace = agency), invite-only user provisioning, and workspace-scoped roles — with Keycloak as the identity provider only.

**Architecture:** Hexagonal, event-sourced module `modules/usermanagement` (package `pl.najem.um`). Two aggregates — `Workspace` (name, memberships, invitations) and `User` (Keycloak subject ↔ NAJEM user, optional ContactId link). Keycloak owns credentials and tokens and nothing else: it is reached through an outbound port (`KeycloakAdminPort`) and the app trusts its JWT only for a subject identifier. **All roles and memberships are NAJEM domain data in the event store** — never Keycloak realm roles or groups. Inbound access enforcement is a resource-server filter plus a `WorkspaceAccess` query other layers consult; modules keep enforcing their own `workspace_id` filters (coordinator ruling seq 21 pt 8).

**Tech Stack:** Java 21, Spring Boot 3.3.5 (`spring-boot-starter-oauth2-resource-server`), Postgres 16 + Flyway, JdbcTemplate (no JPA), Jackson, Keycloak 26 (docker-compose + Testcontainers), JUnit 5, AssertJ, Testcontainers, RestAssured.

## Global Constraints

Copied verbatim from the coordinator's rulings (najem-build seq 19, 20, 21, 23, 26) and Phase 0 conventions. Every task's requirements implicitly include this section.

- **Build env exports, or Testcontainers fails confusingly:**
  ```bash
  export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
  export DOCKER_HOST="unix:///Users/jaca/.colima/default/docker.sock"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="/var/run/docker.sock"
  ```
- **Do NOT touch** in root `build.gradle.kts`: testcontainers-bom pin `1.21.3`, `systemProperty("api.version", "1.44")`, the `-parameters` compiler flag. colima/Docker-29 and Spring `@PathVariable` depend on them.
- **Flyway range: V50–V59 only.** Prefix `classpath:db/um`. `apps/najem-app/src/main/resources/application.yml` already lists `classpath:db/um` — do not edit it.
- **Do NOT edit `settings.gradle.kts`.** `include("modules:usermanagement")` and an empty `modules/usermanagement/build.gradle.kts` are already on main (commit e42b941).
- **Do NOT edit `contracts/`.** New integration events are a CONTRACT-CHANGE-REQUEST on najem-build; the coordinator edits `contracts/` and `PlatformConfig` on ACK. Task 4 is gated on that ACK.
- **Leading-workspaceId convention:** every integration event's first field is `UUID workspaceId` (contract change f283508 established it).
- **Module isolation:** depend only on `contracts` and `platform:eventstore`. Never on another module.
- **No PII in events.** Events carry identifiers only. Invitee email is PII and lives in a lookaside table (decision D3 below), never in an event payload.
- **Register every new event class** in `UmEventTypes.register(...)`.
- **Adapters own wire DTOs.** Never deserialize external JSON (Keycloak admin API responses) into application records.
- **Never `git add -A` / `git add .`** — the checkout is shared. Stage explicit paths only: `git add modules/usermanagement docs/superpowers/plans/2026-08-03-phase1-usermanagement.md`.
- **Work happens in the worktree** `/Users/jaca/Repos/NAJEM-usermgmt` on branch `najem-usermgmt/phase1-usermanagement`. All paths below are relative to it.
- **Test commands:** fast loop `./gradlew :modules:usermanagement:test`; before any STATUS-done post `./gradlew build` (all modules incl. e2e) must be green.
- **Commits:** imperative mood, sign as the repo user, never mention any AI/LLM tool.
- Money = `BigDecimal`, dates = `LocalDate`, instants = `Instant`. No floats.

## Decisions locked before coding

Recorded here so a reviewer contests them now rather than at merge.

- **D1 — Keycloak is IdP-only** (human ruling, reported najem-build seq 25/27). Keycloak stores users, credentials, login flows, tokens. It does NOT store roles, groups or workspace membership. The JWT is trusted for exactly one thing: `sub` (the Keycloak subject UUID). Everything else resolves from `um_user` / `um_membership`. Consequence: NAJEM works against any OIDC provider, and role changes are domain events with an audit trail rather than admin-API side effects.
- **D2 — Workspace is the hard tenancy boundary** (human ruling, seq 21): workspace ≈ agency, holding multiple properties and multiple owners. Cross-workspace reads do not exist in module code.
- **D3 — Invitee email is PII and stays out of events.** `MemberInvited` carries `invitationId`, `workspaceId`, `role`, `invitedByUserId`, `issuedOn`, `expiresOn` — no email, no name. The address lives in `um_invitation_recipient(invitation_id, email)` and is deleted when the invitation is accepted, revoked or expired. This mirrors the PII-lookaside decision without depending on `modules/contacts` (module isolation forbids the dependency, and an invitee is not yet a contact).
- **D4 — Invitation tokens are stored hashed.** The plaintext token is returned once, at issue time, and never persisted. `um_invitation.token_hash` holds SHA-256. A stolen database row cannot be used to accept an invitation.
- **D5 — Roles are a fixed enum in Phase 1: `ADMIN` and `MANAGER`.** Not configurable, not hierarchical, no per-property grants. (Superseded the plan's original four-role proposal: the coordinator ruled from the domain session records that the property OWNER is **not a system user** in MVP, and owner statements / tax packs were cut from scope — so no `OWNER`, `VIEWER` or `ACCOUNTANT` role exists yet. The enum stays open for them to arrive with owner access. najem-build seq 56.)
- **D6 — `UserRegistered` is not an integration event.** Only `WorkspaceCreatedEvent` crosses into `contracts/`, because other modules need to validate that a `workspace_id` exists. Nobody outside this module needs to know a user exists.
- **D7 — A workspace can never exist without an ADMIN.** `WorkspaceService.create` makes the creator an ADMIN in the same transaction and refuses to create a workspace for an unregistered user. Under invite-only, only an ADMIN can invite, so a workspace created without one could never be joined by anybody. (Coordinator ruling, seq 56 — the first-admin bootstrap question raised in this plan's original open-questions section.)
- **D8 — A fresh deployment gets exactly one seeded account.** `PlatformOperator` registers a user from `najem.bootstrap.operator-subject`, `@ConditionalOnProperty` with **no default value**: an implicit operator would be an unauthenticated way into every deployment. This closes the other half of the bootstrap problem — invite-only otherwise leaves a brand-new installation with nobody able to issue the first invitation.

## File Structure

| File | Responsibility |
|---|---|
| `modules/usermanagement/build.gradle.kts` | Module deps (contracts, eventstore, spring web/tx/jdbc, jackson, oauth2-resource-server) |
| `src/main/resources/db/um/V50__um.sql` | Projections: workspaces, users, memberships, invitations, invitation recipients |
| `src/main/java/pl/najem/um/UmEventTypes.java` | Event-type registration |
| `src/main/java/pl/najem/um/domain/Role.java` | Role enum (D5) |
| `src/main/java/pl/najem/um/domain/WorkspaceCreated.java` … `MemberRemoved.java` | Workspace-stream domain events |
| `src/main/java/pl/najem/um/domain/Workspace.java` | Workspace aggregate: memberships + invitation lifecycle, all invariants |
| `src/main/java/pl/najem/um/domain/UserRegistered.java`, `UserLinkedToContact.java` | User-stream domain events |
| `src/main/java/pl/najem/um/domain/User.java` | User aggregate |
| `src/main/java/pl/najem/um/application/WorkspaceService.java` | Create/rename workspace; writes projection + outbox |
| `src/main/java/pl/najem/um/application/UserService.java` | Register user from Keycloak subject; link contact |
| `src/main/java/pl/najem/um/application/InvitationService.java` | Issue / revoke / accept invitations; owns the PII lookaside row |
| `src/main/java/pl/najem/um/application/MembershipService.java` | Change role, remove member |
| `src/main/java/pl/najem/um/application/WorkspaceAccess.java` | Read model: which workspaces + roles a subject has |
| `src/main/java/pl/najem/um/application/KeycloakAdminPort.java` | Outbound port: create a Keycloak user, return its subject |
| `src/main/java/pl/najem/um/adapter/keycloak/KeycloakAdminAdapter.java` | Port impl over the Keycloak admin REST API; owns its wire DTOs |
| `src/main/java/pl/najem/um/adapter/security/SecurityConfig.java` | Resource-server config (module-local, keeps the composition root untouched) |
| `src/main/java/pl/najem/um/adapter/security/CurrentUser.java` | JWT `sub` → NAJEM user + memberships |
| `src/main/java/pl/najem/um/adapter/rest/*.java` | REST adapters (workspaces, invitations, members, me) |
| `docker-compose.yml` (modify) | Add Keycloak service |
| `apps/najem-app/build.gradle.kts` (modify, one line) | `implementation(project(":modules:usermanagement"))` |
| `e2e/src/test/java/pl/najem/e2e/InviteAndAccessTest.java` | e2e: create workspace → invite → accept → access |

Task order matches this table top to bottom, so every task's dependencies are already built.

---

### Task 1: Module scaffold and schema

**Files:**
- Modify: `modules/usermanagement/build.gradle.kts`
- Create: `modules/usermanagement/src/main/resources/db/um/V50__um.sql`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/Role.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/SchemaTest.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: tables `um_workspace`, `um_user`, `um_membership`, `um_invitation`, `um_invitation_recipient`; enum `pl.najem.um.domain.Role` with constants `ADMIN, MANAGER, OWNER, VIEWER`.

- [ ] **Step 1: Write the build file**

`modules/usermanagement/build.gradle.kts` (replaces the empty `plugins { \`java-library\` }` scaffold):

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
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")

    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
```

- [ ] **Step 2: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/SchemaTest.java`:

```java
package pl.najem.um;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void createsUserManagementTables() {
        var tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("um_workspace", "um_user", "um_membership",
            "um_invitation", "um_invitation_recipient");
    }

    @Test
    void scopesMembershipByWorkspaceAndUser() {
        var workspaceId = java.util.UUID.randomUUID();
        var userId = java.util.UUID.randomUUID();
        jdbc.update("insert into um_workspace(workspace_id, name, created_on) values (?,?,current_date)",
            workspaceId, "Agencja Testowa");
        jdbc.update("insert into um_user(user_id, keycloak_subject, registered_on) values (?,?,current_date)",
            userId, java.util.UUID.randomUUID());
        jdbc.update("insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,current_date)",
            workspaceId, userId, "ADMIN");

        assertThat(jdbc.queryForObject(
            "select count(*) from um_membership where workspace_id = ?", Integer.class, workspaceId))
            .isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test`
Expected: FAIL — Flyway finds no migrations in `db/um`, so the tables do not exist.

- [ ] **Step 4: Write the migration**

`modules/usermanagement/src/main/resources/db/um/V50__um.sql`:

```sql
create table um_workspace (
  workspace_id uuid primary key,
  name         text not null,
  created_on   date not null
);

create table um_user (
  user_id          uuid primary key,
  keycloak_subject uuid not null unique,
  contact_id       uuid,
  registered_on    date not null
);

create table um_membership (
  workspace_id uuid not null references um_workspace,
  user_id      uuid not null references um_user,
  role         text not null,
  joined_on    date not null,
  primary key (workspace_id, user_id)
);

create table um_invitation (
  invitation_id       uuid primary key,
  workspace_id        uuid not null references um_workspace,
  role                text not null,
  token_hash          text not null unique,
  status              text not null,
  invited_by_user_id  uuid not null,
  issued_on           date not null,
  expires_on          date not null,
  accepted_by_user_id uuid
);

-- PII lookaside (decision D3): the invitee's address never enters an event payload
-- and this row is deleted on accept, revoke or expiry.
create table um_invitation_recipient (
  invitation_id uuid primary key references um_invitation,
  email         text not null
);

create index um_membership_user_idx on um_membership (user_id);
create index um_invitation_workspace_idx on um_invitation (workspace_id, status);
```

- [ ] **Step 5: Write the Role enum**

`modules/usermanagement/src/main/java/pl/najem/um/domain/Role.java`:

```java
package pl.najem.um.domain;

/**
 * Workspace-scoped roles. NAJEM domain data — never Keycloak realm roles (decision D1).
 * Phase 1 has exactly two (decision D5); the enum stays open for owner access later.
 */
public enum Role {
    ADMIN,
    MANAGER
}
```

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add modules/usermanagement
git commit -m "Add user management module schema and roles"
```

---

### Task 2: Workspace aggregate — creation and renaming

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/WorkspaceCreated.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/WorkspaceRenamed.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/Workspace.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/UmEventTypes.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/domain/WorkspaceTest.java`

**Interfaces:**
- Consumes: `Role` (Task 1).
- Produces:
  ```java
  public record WorkspaceCreated(UUID workspaceId, String name, LocalDate createdOn) {}
  public record WorkspaceRenamed(UUID workspaceId, String name, LocalDate renamedOn) {}
  public class Workspace {                                  // stream type "Workspace"
      public static List<Object> create(UUID workspaceId, String name, LocalDate on);
      public List<Object> rename(String name, LocalDate on);
      public static Workspace from(List<Object> events);
      public UUID id();
      public String name();
  }
  public class UmEventTypes {                               // @Component
      public UmEventTypes(EventTypeRegistry registry);
      public static void register(EventTypeRegistry registry);
  }
  ```

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/domain/WorkspaceTest.java`:

```java
package pl.najem.um.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);

    @Test
    void creationRecordsTheAgencyName() {
        var workspaceId = UUID.randomUUID();

        var events = Workspace.create(workspaceId, "Agencja Krakowska", TODAY);

        assertThat(events).containsExactly(new WorkspaceCreated(workspaceId, "Agencja Krakowska", TODAY));
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> Workspace.create(UUID.randomUUID(), "  ", TODAY))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renamingReplaysIntoTheNewName() {
        var workspaceId = UUID.randomUUID();
        var workspace = Workspace.from(Workspace.create(workspaceId, "Stara Nazwa", TODAY));

        var events = workspace.rename("Nowa Nazwa", TODAY);

        assertThat(events).containsExactly(new WorkspaceRenamed(workspaceId, "Nowa Nazwa", TODAY));
        assertThat(Workspace.from(java.util.stream.Stream.concat(
                Workspace.create(workspaceId, "Stara Nazwa", TODAY).stream(), events.stream()).toList())
            .name()).isEqualTo("Nowa Nazwa");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*WorkspaceTest'`
Expected: FAIL — `Workspace`, `WorkspaceCreated`, `WorkspaceRenamed` do not exist (compilation error).

- [ ] **Step 3: Write the events**

`WorkspaceCreated.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record WorkspaceCreated(UUID workspaceId, String name, LocalDate createdOn) {}
```

`WorkspaceRenamed.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record WorkspaceRenamed(UUID workspaceId, String name, LocalDate renamedOn) {}
```

- [ ] **Step 4: Write the aggregate**

`Workspace.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Event-sourced aggregate; stream type "Workspace". The agency that owns properties, ledgers and people. */
public class Workspace {

    private UUID id;
    private String name;

    private Workspace() {}

    public static List<Object> create(UUID workspaceId, String name, LocalDate on) {
        requireName(name);
        return List.of(new WorkspaceCreated(workspaceId, name.trim(), on));
    }

    public List<Object> rename(String newName, LocalDate on) {
        requireName(newName);
        return List.of(new WorkspaceRenamed(id, newName.trim(), on));
    }

    public static Workspace from(List<Object> events) {
        var workspace = new Workspace();
        events.forEach(workspace::apply);
        return workspace;
    }

    private void apply(Object event) {
        if (event instanceof WorkspaceCreated e) {
            id = e.workspaceId();
            name = e.name();
        } else if (event instanceof WorkspaceRenamed e) {
            name = e.name();
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workspace name must not be blank");
        }
    }

    public UUID id() { return id; }

    public String name() { return name; }
}
```

- [ ] **Step 5: Write UmEventTypes**

`modules/usermanagement/src/main/java/pl/najem/um/UmEventTypes.java`:

```java
package pl.najem.um;

import org.springframework.stereotype.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.um.domain.WorkspaceCreated;
import pl.najem.um.domain.WorkspaceRenamed;

@Component
public class UmEventTypes {

    public UmEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(WorkspaceCreated.class);
        registry.register(WorkspaceRenamed.class);
    }
}
```

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test --tests '*WorkspaceTest'`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add modules/usermanagement
git commit -m "Add workspace aggregate with creation and renaming"
```

---

### Task 3: CONTRACT-CHANGE-REQUEST for WorkspaceCreatedEvent

**Files:** none in this repo — this task is a coordination step. It exists as its own task because Task 4 cannot compile until the coordinator lands the contract.

**Interfaces:**
- Produces (after coordinator ACK, in `contracts/`, coordinator-authored):
  ```java
  package pl.najem.contracts.events;

  public record WorkspaceCreatedEvent(UUID workspaceId, String name) implements IntegrationEvent {}
  ```

- [ ] **Step 1: Post the request to najem-build**

Post from `najem-usermgmt`, body:

> CONTRACT-CHANGE-REQUEST — `WorkspaceCreatedEvent`. Exact shape:
> `public record WorkspaceCreatedEvent(UUID workspaceId, String name) implements IntegrationEvent {}`
> in `pl.najem.contracts.events`. Leading-workspaceId convention satisfied. `name` is the agency's business name — not PII (an organisation, not a person). Rationale: seq 21 pt 6 — other modules must be able to validate that a `workspace_id` refers to a workspace that exists before scoping rows to it. Emitted by `WorkspaceService.create` into the outbox in the same transaction as `WorkspaceCreated`. No other integration events from usermanagement in Phase 1.

- [ ] **Step 2: Wait for the coordinator's ACK and the landing commit**

Do not proceed to Task 4 until the coordinator posts that `contracts/` and `PlatformConfig` are updated. Then:

```bash
git fetch && git rebase origin/main
```

(If the repo has no remote, rebase on local `main`: `git rebase main`.)

- [ ] **Step 3: Verify the contract is present**

Run: `ls contracts/src/main/java/pl/najem/contracts/events/WorkspaceCreatedEvent.java`
Expected: the file exists.

If the coordinator rejects or reshapes the event, update Task 4's code to the ACKed shape before implementing it.

---

### Task 4: WorkspaceService — persist, project, publish

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/application/WorkspaceService.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/application/WorkspaceServiceTest.java`

**Interfaces:**
- Consumes: `Workspace`, `WorkspaceCreated`, `WorkspaceRenamed` (Task 2); `WorkspaceCreatedEvent` (Task 3); `EventStore`, `JdbcTemplate`.
- Produces:
  ```java
  public class WorkspaceService {
      public WorkspaceService(EventStore store, JdbcTemplate jdbc);
      public UUID create(String name, LocalDate on);   // stream: workspaceId, type "Workspace"
      public void rename(UUID workspaceId, String name, LocalDate on);
  }
  ```

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/application/WorkspaceServiceTest.java`:

```java
package pl.najem.um.application;

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
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static WorkspaceService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        registry.register(WorkspaceCreatedEvent.class);
        service = new WorkspaceService(
            new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry), jdbc);
    }

    @Test
    void createProjectsTheWorkspaceAndPublishesTheIntegrationEvent() {
        var workspaceId = service.create("Agencja Krakowska", LocalDate.of(2026, 8, 3));

        assertThat(jdbc.queryForObject("select name from um_workspace where workspace_id = ?",
            String.class, workspaceId)).isEqualTo("Agencja Krakowska");
        var outboxTypes = jdbc.queryForList("select event_type from outbox", String.class);
        assertThat(outboxTypes).contains("WorkspaceCreatedEvent");
        var payload = jdbc.queryForObject(
            "select payload::text from outbox where event_type = 'WorkspaceCreatedEvent'", String.class);
        assertThat(payload).contains(workspaceId.toString()).contains("Agencja Krakowska");
    }

    @Test
    void renameUpdatesTheProjectionWithoutRepublishing() {
        var workspaceId = service.create("Stara Agencja", LocalDate.of(2026, 8, 3));
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox", Integer.class);

        service.rename(workspaceId, "Nowa Agencja", LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject("select name from um_workspace where workspace_id = ?",
            String.class, workspaceId)).isEqualTo("Nowa Agencja");
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(outboxBefore);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*WorkspaceServiceTest'`
Expected: FAIL — `WorkspaceService` does not exist.

- [ ] **Step 3: Write the service**

`WorkspaceService.java`:

```java
package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.Workspace;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class WorkspaceService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public WorkspaceService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID create(String name, LocalDate on) {
        UUID workspaceId = UUID.randomUUID();
        var events = Workspace.create(workspaceId, name, on);
        var created = Workspace.from(events);
        store.append(workspaceId, "Workspace", 0, events,
            List.of(new WorkspaceCreatedEvent(workspaceId, created.name())));
        jdbc.update("insert into um_workspace(workspace_id, name, created_on) values (?,?,?)",
            workspaceId, created.name(), on);
        return workspaceId;
    }

    public void rename(UUID workspaceId, String name, LocalDate on) {
        var stream = store.load(workspaceId);
        var workspace = Workspace.from(stream.events());
        var events = workspace.rename(name, on);
        store.append(workspaceId, "Workspace", stream.version(), events, List.of());
        jdbc.update("update um_workspace set name = ? where workspace_id = ?",
            Workspace.from(java.util.stream.Stream.concat(stream.events().stream(), events.stream()).toList())
                .name(), workspaceId);
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test --tests '*WorkspaceServiceTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add modules/usermanagement
git commit -m "Create workspaces with projection and integration event"
```

---

### Task 5: User aggregate and registration from a Keycloak subject

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/UserRegistered.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/UserLinkedToContact.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/User.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/application/UserService.java`
- Modify: `modules/usermanagement/src/main/java/pl/najem/um/UmEventTypes.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/application/UserServiceTest.java`

**Interfaces:**
- Consumes: `EventStore`, `JdbcTemplate`, `um_user` (Task 1).
- Produces:
  ```java
  public record UserRegistered(UUID userId, UUID keycloakSubject, LocalDate registeredOn) {}
  public record UserLinkedToContact(UUID userId, UUID contactId, LocalDate linkedOn) {}
  public class User {                                        // stream type "User"
      public static List<Object> register(UUID userId, UUID keycloakSubject, LocalDate on);
      public List<Object> linkContact(UUID contactId, LocalDate on);   // IllegalStateException if already linked
      public static User from(List<Object> events);
      public UUID id();
      public UUID keycloakSubject();
      public UUID contactId();                               // null when unlinked
  }
  public class UserService {
      public UserService(EventStore store, JdbcTemplate jdbc);
      public UUID register(UUID keycloakSubject, LocalDate on);
      public void linkContact(UUID userId, UUID contactId, LocalDate on);
      public Optional<UUID> findBySubject(UUID keycloakSubject);
  }
  ```

Note the D6 boundary: no integration event here. And per the seq 14/27 settlement, the `UserId ↔ ContactId` link lives on this side; `modules/contacts` stays link-free and no name/email is copied here.

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/application/UserServiceTest.java`:

```java
package pl.najem.um.application;

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
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class UserServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static UserService service;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        service = new UserService(
            new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry), jdbc);
    }

    @Test
    void registrationIsLookupableBySubject() {
        var subject = UUID.randomUUID();

        var userId = service.register(subject, LocalDate.of(2026, 8, 3));

        assertThat(service.findBySubject(subject)).contains(userId);
    }

    @Test
    void registrationStoresNoPersonalData() {
        var subject = UUID.randomUUID();
        var userId = service.register(subject, LocalDate.of(2026, 8, 3));

        var payloads = jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, userId);
        assertThat(payloads).allSatisfy(p -> assertThat(p).doesNotContain("@"));
    }

    @Test
    void contactLinkIsStoredOnTheUser() {
        var contactId = UUID.randomUUID();
        var userId = service.register(UUID.randomUUID(), LocalDate.of(2026, 8, 3));

        service.linkContact(userId, contactId, LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject("select contact_id from um_user where user_id = ?",
            UUID.class, userId)).isEqualTo(contactId);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*UserServiceTest'`
Expected: FAIL — `UserService` does not exist.

- [ ] **Step 3: Write the events and aggregate**

`UserRegistered.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record UserRegistered(UUID userId, UUID keycloakSubject, LocalDate registeredOn) {}
```

`UserLinkedToContact.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record UserLinkedToContact(UUID userId, UUID contactId, LocalDate linkedOn) {}
```

`User.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Event-sourced aggregate; stream type "User". Holds NO personal data — Keycloak owns
 * credentials and profile (decision D1), Contacts owns person data (seq 14 settlement).
 */
public class User {

    private UUID id;
    private UUID keycloakSubject;
    private UUID contactId;

    private User() {}

    public static List<Object> register(UUID userId, UUID keycloakSubject, LocalDate on) {
        if (keycloakSubject == null) {
            throw new IllegalArgumentException("keycloak subject is required");
        }
        return List.of(new UserRegistered(userId, keycloakSubject, on));
    }

    public List<Object> linkContact(UUID contactId, LocalDate on) {
        if (this.contactId != null) {
            throw new IllegalStateException("user is already linked to a contact");
        }
        return List.of(new UserLinkedToContact(id, contactId, on));
    }

    public static User from(List<Object> events) {
        var user = new User();
        events.forEach(user::apply);
        return user;
    }

    private void apply(Object event) {
        if (event instanceof UserRegistered e) {
            id = e.userId();
            keycloakSubject = e.keycloakSubject();
        } else if (event instanceof UserLinkedToContact e) {
            contactId = e.contactId();
        }
    }

    public UUID id() { return id; }

    public UUID keycloakSubject() { return keycloakSubject; }

    public UUID contactId() { return contactId; }
}
```

- [ ] **Step 4: Write the service**

`UserService.java`:

```java
package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public UserService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public UUID register(UUID keycloakSubject, LocalDate on) {
        UUID userId = UUID.randomUUID();
        store.append(userId, "User", 0, User.register(userId, keycloakSubject, on), List.of());
        jdbc.update("insert into um_user(user_id, keycloak_subject, registered_on) values (?,?,?)",
            userId, keycloakSubject, on);
        return userId;
    }

    public void linkContact(UUID userId, UUID contactId, LocalDate on) {
        var stream = store.load(userId);
        var user = User.from(stream.events());
        store.append(userId, "User", stream.version(), user.linkContact(contactId, on), List.of());
        jdbc.update("update um_user set contact_id = ? where user_id = ?", contactId, userId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findBySubject(UUID keycloakSubject) {
        return jdbc.query("select user_id from um_user where keycloak_subject = ?",
            (rs, i) -> rs.getObject(1, UUID.class), keycloakSubject).stream().findFirst();
    }
}
```

- [ ] **Step 5: Register the new event types**

In `UmEventTypes.register(...)`, add:

```java
        registry.register(UserRegistered.class);
        registry.register(UserLinkedToContact.class);
```

with the matching imports `pl.najem.um.domain.UserRegistered` and `pl.najem.um.domain.UserLinkedToContact`.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test --tests '*UserServiceTest'`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add modules/usermanagement
git commit -m "Register users against Keycloak subjects and link contacts"
```

---

### Task 6: Invitation issuing — hashed token, PII lookaside

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/MemberInvited.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/InvitationRevoked.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/application/InvitationService.java`
- Modify: `modules/usermanagement/src/main/java/pl/najem/um/UmEventTypes.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/application/InvitationServiceTest.java`

**Interfaces:**
- Consumes: `WorkspaceService` (Task 4), `Role` (Task 1), `um_invitation`, `um_invitation_recipient`.
- Produces:
  ```java
  public record MemberInvited(UUID workspaceId, UUID invitationId, Role role,
                              UUID invitedByUserId, LocalDate issuedOn, LocalDate expiresOn) {}
  public record InvitationRevoked(UUID workspaceId, UUID invitationId, LocalDate revokedOn) {}
  public class InvitationService {
      public record Issued(UUID invitationId, String token) {}   // token returned once, never stored (D4)
      public InvitationService(EventStore store, JdbcTemplate jdbc);
      public Issued invite(UUID workspaceId, String email, Role role, UUID invitedByUserId,
                           LocalDate on, LocalDate expiresOn);
      public void revoke(UUID workspaceId, UUID invitationId, LocalDate on);
      public static String hash(String token);
  }
  ```

The invitation lives on the Workspace stream (invitations are a workspace concern, and this keeps the accept path's optimistic locking on one stream).

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/application/InvitationServiceTest.java`:

```java
package pl.najem.um.application;

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
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InvitationServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static WorkspaceService workspaces;
    static InvitationService invitations;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        registry.register(WorkspaceCreatedEvent.class);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        workspaces = new WorkspaceService(store, jdbc);
        invitations = new InvitationService(store, jdbc);
    }

    @Test
    void issuedInvitationIsPendingAndCarriesOnlyAHashedToken() {
        var workspaceId = workspaces.create("Agencja Testowa", TODAY);

        var issued = invitations.invite(workspaceId, "nowy@example.com", Role.MANAGER,
            UUID.randomUUID(), TODAY, EXPIRY);

        assertThat(issued.token()).isNotBlank();
        assertThat(jdbc.queryForObject("select status from um_invitation where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select token_hash from um_invitation where invitation_id = ?",
            String.class, issued.invitationId()))
            .isEqualTo(InvitationService.hash(issued.token()))
            .isNotEqualTo(issued.token());
    }

    @Test
    void emailLivesOnlyInTheLookasideNeverInAnEvent() {
        var workspaceId = workspaces.create("Agencja PII", TODAY);

        var issued = invitations.invite(workspaceId, "prywatny@example.com", Role.VIEWER,
            UUID.randomUUID(), TODAY, EXPIRY);

        assertThat(jdbc.queryForObject("select email from um_invitation_recipient where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("prywatny@example.com");
        var payloads = jdbc.queryForList(
            "select payload::text from events where stream_id = ?", String.class, workspaceId);
        assertThat(payloads).allSatisfy(p -> assertThat(p).doesNotContain("prywatny@example.com"));
    }

    @Test
    void revokingDropsTheRecipientRowAndMarksTheInvitation() {
        var workspaceId = workspaces.create("Agencja Revoke", TODAY);
        var issued = invitations.invite(workspaceId, "odwolany@example.com", Role.VIEWER,
            UUID.randomUUID(), TODAY, EXPIRY);

        invitations.revoke(workspaceId, issued.invitationId(), LocalDate.of(2026, 8, 5));

        assertThat(jdbc.queryForObject("select status from um_invitation where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
            "select count(*) from um_invitation_recipient where invitation_id = ?",
            Integer.class, issued.invitationId())).isZero();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*InvitationServiceTest'`
Expected: FAIL — `InvitationService` does not exist.

- [ ] **Step 3: Write the events**

`MemberInvited.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

/** No email, no name — the invitee's address lives in the um_invitation_recipient lookaside (D3). */
public record MemberInvited(UUID workspaceId, UUID invitationId, Role role,
                            UUID invitedByUserId, LocalDate issuedOn, LocalDate expiresOn) {}
```

`InvitationRevoked.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record InvitationRevoked(UUID workspaceId, UUID invitationId, LocalDate revokedOn) {}
```

- [ ] **Step 4: Write the service**

`InvitationService.java`:

```java
package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.InvitationRevoked;
import pl.najem.um.domain.MemberInvited;
import pl.najem.um.domain.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class InvitationService {

    /** The plaintext token is handed out once here and never stored (decision D4). */
    public record Issued(UUID invitationId, String token) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public InvitationService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public Issued invite(UUID workspaceId, String email, Role role, UUID invitedByUserId,
                         LocalDate on, LocalDate expiresOn) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("invitee email is required");
        }
        if (expiresOn.isBefore(on)) {
            throw new IllegalArgumentException("invitation cannot expire before it is issued");
        }
        UUID invitationId = UUID.randomUUID();
        String token = newToken();

        var stream = store.load(workspaceId);
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberInvited(workspaceId, invitationId, role, invitedByUserId, on, expiresOn)),
            List.of());

        jdbc.update("""
            insert into um_invitation(invitation_id, workspace_id, role, token_hash, status,
                                      invited_by_user_id, issued_on, expires_on)
            values (?,?,?,?,'PENDING',?,?,?)
            """, invitationId, workspaceId, role.name(), hash(token), invitedByUserId, on, expiresOn);
        jdbc.update("insert into um_invitation_recipient(invitation_id, email) values (?,?)",
            invitationId, email);
        return new Issued(invitationId, token);
    }

    public void revoke(UUID workspaceId, UUID invitationId, LocalDate on) {
        var stream = store.load(workspaceId);
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new InvitationRevoked(workspaceId, invitationId, on)), List.of());
        jdbc.update("update um_invitation set status = 'REVOKED' where invitation_id = ? and status = 'PENDING'",
            invitationId);
        jdbc.update("delete from um_invitation_recipient where invitation_id = ?", invitationId);
    }

    public static String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 5: Register the new event types**

In `UmEventTypes.register(...)` add `registry.register(MemberInvited.class);` and `registry.register(InvitationRevoked.class);` with matching imports.

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test --tests '*InvitationServiceTest'`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add modules/usermanagement
git commit -m "Issue and revoke workspace invitations with hashed tokens"
```

---

### Task 7: Accepting an invitation — provisioning and membership

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/InvitationAccepted.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/application/KeycloakAdminPort.java`
- Modify: `modules/usermanagement/src/main/java/pl/najem/um/application/InvitationService.java`
- Modify: `modules/usermanagement/src/main/java/pl/najem/um/UmEventTypes.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/application/InvitationAcceptanceTest.java`

**Interfaces:**
- Consumes: `UserService` (Task 5), `InvitationService.hash` (Task 6), `um_membership`.
- Produces:
  ```java
  public record InvitationAccepted(UUID workspaceId, UUID invitationId, UUID userId, LocalDate acceptedOn) {}
  public interface KeycloakAdminPort {
      /** Creates the Keycloak user (or returns the existing subject for this email) and returns its subject id. */
      UUID provision(String email);
  }
  // added to InvitationService:
  public UUID accept(String token, LocalDate on);   // returns the userId; throws IllegalStateException when
                                                    // the token is unknown, already used, revoked or expired
  ```

This is where invite-only provisioning becomes real: there is no other path that creates a membership.

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/application/InvitationAcceptanceTest.java`:

```java
package pl.najem.um.application;

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
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class InvitationAcceptanceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static WorkspaceService workspaces;
    static InvitationService invitations;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);

    /** Fake adapter: one Keycloak subject per email, stable across calls. */
    static final java.util.Map<String, UUID> KEYCLOAK = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        registry.register(WorkspaceCreatedEvent.class);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        workspaces = new WorkspaceService(store, jdbc);
        invitations = new InvitationService(store, jdbc, new UserService(store, jdbc),
            email -> KEYCLOAK.computeIfAbsent(email, e -> UUID.randomUUID()));
    }

    @Test
    void acceptingProvisionsTheUserAndGrantsTheInvitedRole() {
        var workspaceId = workspaces.create("Agencja Accept", TODAY);
        var issued = invitations.invite(workspaceId, "manager@example.com", Role.MANAGER,
            UUID.randomUUID(), TODAY, EXPIRY);

        var userId = invitations.accept(issued.token(), LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject(
            "select role from um_membership where workspace_id = ? and user_id = ?",
            String.class, workspaceId, userId)).isEqualTo("MANAGER");
        assertThat(jdbc.queryForObject("select keycloak_subject from um_user where user_id = ?",
            UUID.class, userId)).isEqualTo(KEYCLOAK.get("manager@example.com"));
        assertThat(jdbc.queryForObject("select status from um_invitation where invitation_id = ?",
            String.class, issued.invitationId())).isEqualTo("ACCEPTED");
    }

    @Test
    void acceptingErasesTheRecipientLookaside() {
        var workspaceId = workspaces.create("Agencja Erase", TODAY);
        var issued = invitations.invite(workspaceId, "erase@example.com", Role.VIEWER,
            UUID.randomUUID(), TODAY, EXPIRY);

        invitations.accept(issued.token(), LocalDate.of(2026, 8, 4));

        assertThat(jdbc.queryForObject(
            "select count(*) from um_invitation_recipient where invitation_id = ?",
            Integer.class, issued.invitationId())).isZero();
    }

    @Test
    void aTokenCannotBeUsedTwice() {
        var workspaceId = workspaces.create("Agencja Reuse", TODAY);
        var issued = invitations.invite(workspaceId, "reuse@example.com", Role.VIEWER,
            UUID.randomUUID(), TODAY, EXPIRY);
        invitations.accept(issued.token(), LocalDate.of(2026, 8, 4));

        assertThatThrownBy(() -> invitations.accept(issued.token(), LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        var workspaceId = workspaces.create("Agencja Expiry", TODAY);
        var issued = invitations.invite(workspaceId, "late@example.com", Role.VIEWER,
            UUID.randomUUID(), TODAY, LocalDate.of(2026, 8, 4));

        assertThatThrownBy(() -> invitations.accept(issued.token(), LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() -> invitations.accept("not-a-real-token", TODAY))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExistingUserJoinsASecondWorkspaceWithoutReprovisioning() {
        var first = workspaces.create("Agencja Pierwsza", TODAY);
        var second = workspaces.create("Agencja Druga", TODAY);
        var firstInvite = invitations.invite(first, "wielo@example.com", Role.OWNER,
            UUID.randomUUID(), TODAY, EXPIRY);
        var userId = invitations.accept(firstInvite.token(), LocalDate.of(2026, 8, 4));
        var secondInvite = invitations.invite(second, "wielo@example.com", Role.VIEWER,
            UUID.randomUUID(), TODAY, EXPIRY);

        var sameUser = invitations.accept(secondInvite.token(), LocalDate.of(2026, 8, 5));

        assertThat(sameUser).isEqualTo(userId);
        assertThat(jdbc.queryForObject("select count(*) from um_membership where user_id = ?",
            Integer.class, userId)).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*InvitationAcceptanceTest'`
Expected: FAIL — `InvitationService` has no four-argument constructor and no `accept`.

- [ ] **Step 3: Write the port and the event**

`KeycloakAdminPort.java`:

```java
package pl.najem.um.application;

import java.util.UUID;

/**
 * Outbound port to the identity provider. Keycloak owns credentials and login only (decision D1):
 * this port never reads or writes roles, groups or workspace membership.
 */
public interface KeycloakAdminPort {

    /** Creates the user if absent and returns its Keycloak subject id; idempotent per email. */
    UUID provision(String email);
}
```

`InvitationAccepted.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record InvitationAccepted(UUID workspaceId, UUID invitationId, UUID userId, LocalDate acceptedOn) {}
```

- [ ] **Step 4: Extend InvitationService**

Add the two collaborators to the constructor and the `accept` method. Replace the constructor and add the method:

```java
    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final UserService users;
    private final KeycloakAdminPort keycloak;

    public InvitationService(EventStore store, JdbcTemplate jdbc, UserService users,
                             KeycloakAdminPort keycloak) {
        this.store = store;
        this.jdbc = jdbc;
        this.users = users;
        this.keycloak = keycloak;
    }

    public UUID accept(String token, LocalDate on) {
        var rows = jdbc.query("""
            select i.invitation_id, i.workspace_id, i.role, i.status, i.expires_on, r.email
            from um_invitation i left join um_invitation_recipient r on r.invitation_id = i.invitation_id
            where i.token_hash = ?
            """, (rs, i) -> new Object[]{
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getObject(5, LocalDate.class), rs.getString(6)
            }, hash(token));
        if (rows.isEmpty()) {
            throw new IllegalStateException("unknown invitation token");
        }
        var row = rows.getFirst();
        UUID invitationId = (UUID) row[0];
        UUID workspaceId = (UUID) row[1];
        Role role = Role.valueOf((String) row[2]);
        String status = (String) row[3];
        LocalDate expiresOn = (LocalDate) row[4];
        String email = (String) row[5];

        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("invitation is not pending: " + status);
        }
        if (on.isAfter(expiresOn)) {
            throw new IllegalStateException("invitation expired on " + expiresOn);
        }

        UUID subject = keycloak.provision(email);
        UUID userId = users.findBySubject(subject).orElseGet(() -> users.register(subject, on));

        var stream = store.load(workspaceId);
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new InvitationAccepted(workspaceId, invitationId, userId, on)), List.of());

        jdbc.update("""
            insert into um_membership(workspace_id, user_id, role, joined_on) values (?,?,?,?)
            on conflict (workspace_id, user_id) do update set role = excluded.role
            """, workspaceId, userId, role.name(), on);
        jdbc.update("update um_invitation set status = 'ACCEPTED', accepted_by_user_id = ? where invitation_id = ?",
            userId, invitationId);
        jdbc.update("delete from um_invitation_recipient where invitation_id = ?", invitationId);
        return userId;
    }
```

Add imports `pl.najem.um.domain.InvitationAccepted` and `java.util.UUID` if not already present.

- [ ] **Step 5: Register the event type**

Add `registry.register(InvitationAccepted.class);` to `UmEventTypes.register(...)`.

- [ ] **Step 6: Run all module tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test`
Expected: PASS — including the earlier `InvitationServiceTest`, which must be updated to the four-argument constructor:

```java
        invitations = new InvitationService(store, jdbc, new UserService(store, jdbc),
            email -> UUID.randomUUID());
```

- [ ] **Step 7: Commit**

```bash
git add modules/usermanagement
git commit -m "Accept invitations to provision users and grant membership"
```

---

### Task 8: Membership changes and the access read model

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/MemberRoleChanged.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/domain/MemberRemoved.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/application/MembershipService.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/application/WorkspaceAccess.java`
- Modify: `modules/usermanagement/src/main/java/pl/najem/um/UmEventTypes.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/application/MembershipServiceTest.java`

**Interfaces:**
- Consumes: `um_membership`, `um_user`, `Role`, `EventStore`.
- Produces:
  ```java
  public record MemberRoleChanged(UUID workspaceId, UUID userId, Role role, LocalDate changedOn) {}
  public record MemberRemoved(UUID workspaceId, UUID userId, LocalDate removedOn) {}
  public class MembershipService {
      public MembershipService(EventStore store, JdbcTemplate jdbc);
      public void changeRole(UUID workspaceId, UUID userId, Role role, LocalDate on);
      public void remove(UUID workspaceId, UUID userId, LocalDate on);   // refuses the last ADMIN
  }
  public class WorkspaceAccess {
      public record Membership(UUID workspaceId, Role role) {}
      public WorkspaceAccess(JdbcTemplate jdbc);
      public List<Membership> forSubject(UUID keycloakSubject);
      public Optional<Role> roleIn(UUID keycloakSubject, UUID workspaceId);
      public boolean canAccess(UUID keycloakSubject, UUID workspaceId);
  }
  ```

`WorkspaceAccess` is the read model the security layer and other adapters consult. It is deliberately a query object, not a Spring Security integration — that keeps it testable without a servlet stack.

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/application/MembershipServiceTest.java`:

```java
package pl.najem.um.application;

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
import pl.najem.contracts.events.WorkspaceCreatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;
import pl.najem.um.UmEventTypes;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MembershipServiceTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static WorkspaceService workspaces;
    static InvitationService invitations;
    static MembershipService memberships;
    static WorkspaceAccess access;
    static final java.util.Map<String, UUID> KEYCLOAK = new java.util.concurrent.ConcurrentHashMap<>();

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 3);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 17);

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/um").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        UmEventTypes.register(registry);
        registry.register(WorkspaceCreatedEvent.class);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        workspaces = new WorkspaceService(store, jdbc);
        invitations = new InvitationService(store, jdbc, new UserService(store, jdbc),
            email -> KEYCLOAK.computeIfAbsent(email, e -> UUID.randomUUID()));
        memberships = new MembershipService(store, jdbc);
        access = new WorkspaceAccess(jdbc);
    }

    private static UUID member(UUID workspaceId, String email, Role role) {
        var issued = invitations.invite(workspaceId, email, role, UUID.randomUUID(), TODAY, EXPIRY);
        return invitations.accept(issued.token(), TODAY);
    }

    @Test
    void roleChangeIsVisibleThroughTheAccessReadModel() {
        var workspaceId = workspaces.create("Agencja Role", TODAY);
        var adminId = member(workspaceId, "admin-role@example.com", Role.ADMIN);
        var userId = member(workspaceId, "viewer-role@example.com", Role.VIEWER);
        assertThat(adminId).isNotEqualTo(userId);

        memberships.changeRole(workspaceId, userId, Role.MANAGER, LocalDate.of(2026, 8, 5));

        assertThat(access.roleIn(KEYCLOAK.get("viewer-role@example.com"), workspaceId))
            .contains(Role.MANAGER);
    }

    @Test
    void removedMemberLosesAccess() {
        var workspaceId = workspaces.create("Agencja Remove", TODAY);
        member(workspaceId, "admin-remove@example.com", Role.ADMIN);
        var userId = member(workspaceId, "leaving@example.com", Role.VIEWER);

        memberships.remove(workspaceId, userId, LocalDate.of(2026, 8, 5));

        assertThat(access.canAccess(KEYCLOAK.get("leaving@example.com"), workspaceId)).isFalse();
    }

    @Test
    void theLastAdminCannotBeRemoved() {
        var workspaceId = workspaces.create("Agencja Ostatni", TODAY);
        var adminId = member(workspaceId, "sole-admin@example.com", Role.ADMIN);

        assertThatThrownBy(() -> memberships.remove(workspaceId, adminId, LocalDate.of(2026, 8, 5)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accessNeverLeaksAcrossWorkspaces() {
        var mine = workspaces.create("Agencja Moja", TODAY);
        var theirs = workspaces.create("Agencja Obca", TODAY);
        member(mine, "mine@example.com", Role.MANAGER);
        member(theirs, "theirs@example.com", Role.MANAGER);

        assertThat(access.canAccess(KEYCLOAK.get("mine@example.com"), theirs)).isFalse();
        assertThat(access.forSubject(KEYCLOAK.get("mine@example.com")))
            .extracting(WorkspaceAccess.Membership::workspaceId)
            .containsExactly(mine);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*MembershipServiceTest'`
Expected: FAIL — `MembershipService` and `WorkspaceAccess` do not exist.

- [ ] **Step 3: Write the events**

`MemberRoleChanged.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record MemberRoleChanged(UUID workspaceId, UUID userId, Role role, LocalDate changedOn) {}
```

`MemberRemoved.java`:

```java
package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record MemberRemoved(UUID workspaceId, UUID userId, LocalDate removedOn) {}
```

- [ ] **Step 4: Write MembershipService**

`MembershipService.java`:

```java
package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.eventstore.EventStore;
import pl.najem.um.domain.MemberRemoved;
import pl.najem.um.domain.MemberRoleChanged;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class MembershipService {

    private final EventStore store;
    private final JdbcTemplate jdbc;

    public MembershipService(EventStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    public void changeRole(UUID workspaceId, UUID userId, Role role, LocalDate on) {
        requireMember(workspaceId, userId);
        if (role != Role.ADMIN) {
            requireAnotherAdminIfDemoting(workspaceId, userId);
        }
        var stream = store.load(workspaceId);
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberRoleChanged(workspaceId, userId, role, on)), List.of());
        jdbc.update("update um_membership set role = ? where workspace_id = ? and user_id = ?",
            role.name(), workspaceId, userId);
    }

    public void remove(UUID workspaceId, UUID userId, LocalDate on) {
        requireMember(workspaceId, userId);
        requireAnotherAdminIfDemoting(workspaceId, userId);
        var stream = store.load(workspaceId);
        store.append(workspaceId, "Workspace", stream.version(),
            List.of(new MemberRemoved(workspaceId, userId, on)), List.of());
        jdbc.update("delete from um_membership where workspace_id = ? and user_id = ?", workspaceId, userId);
    }

    private void requireMember(UUID workspaceId, UUID userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from um_membership where workspace_id = ? and user_id = ?",
            Integer.class, workspaceId, userId);
        if (count == null || count == 0) {
            throw new IllegalStateException("user is not a member of this workspace");
        }
    }

    /** A workspace must always keep at least one ADMIN, or nobody can ever invite again. */
    private void requireAnotherAdminIfDemoting(UUID workspaceId, UUID userId) {
        Integer otherAdmins = jdbc.queryForObject("""
            select count(*) from um_membership
            where workspace_id = ? and role = 'ADMIN' and user_id <> ?
            """, Integer.class, workspaceId, userId);
        Boolean isAdmin = jdbc.queryForObject("""
            select role = 'ADMIN' from um_membership where workspace_id = ? and user_id = ?
            """, Boolean.class, workspaceId, userId);
        if (Boolean.TRUE.equals(isAdmin) && (otherAdmins == null || otherAdmins == 0)) {
            throw new IllegalStateException("workspace must keep at least one admin");
        }
    }
}
```

- [ ] **Step 5: Write WorkspaceAccess**

`WorkspaceAccess.java`:

```java
package pl.najem.um.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.um.domain.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model answering "what may this Keycloak subject reach?". Roles come from NAJEM's own
 * projection, never from token claims (decision D1).
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceAccess {

    public record Membership(UUID workspaceId, Role role) {}

    private final JdbcTemplate jdbc;

    public WorkspaceAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Membership> forSubject(UUID keycloakSubject) {
        return jdbc.query("""
            select m.workspace_id, m.role
            from um_membership m join um_user u on u.user_id = m.user_id
            where u.keycloak_subject = ?
            order by m.joined_on, m.workspace_id
            """, (rs, i) -> new Membership(rs.getObject(1, UUID.class), Role.valueOf(rs.getString(2))),
            keycloakSubject);
    }

    public Optional<Role> roleIn(UUID keycloakSubject, UUID workspaceId) {
        return forSubject(keycloakSubject).stream()
            .filter(m -> m.workspaceId().equals(workspaceId))
            .map(Membership::role)
            .findFirst();
    }

    public boolean canAccess(UUID keycloakSubject, UUID workspaceId) {
        return roleIn(keycloakSubject, workspaceId).isPresent();
    }
}
```

- [ ] **Step 6: Register the event types**

Add `registry.register(MemberRoleChanged.class);` and `registry.register(MemberRemoved.class);` to `UmEventTypes.register(...)`.

- [ ] **Step 7: Run all module tests and confirm they pass**

Run: `./gradlew :modules:usermanagement:test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add modules/usermanagement
git commit -m "Change member roles and resolve workspace access"
```

---

### Task 9: Keycloak admin adapter

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/adapter/keycloak/KeycloakAdminAdapter.java`
- Test: `modules/usermanagement/src/test/java/pl/najem/um/adapter/keycloak/KeycloakAdminAdapterTest.java`

**Interfaces:**
- Consumes: `KeycloakAdminPort` (Task 7).
- Produces: `KeycloakAdminAdapter implements KeycloakAdminPort`, a `@Component` active when `najem.keycloak.base-url` is set. Configuration keys: `najem.keycloak.base-url`, `najem.keycloak.realm`, `najem.keycloak.admin-client-id`, `najem.keycloak.admin-client-secret`.

Per convention 4 the adapter owns its wire DTOs — Keycloak's JSON never becomes an application record.

- [ ] **Step 1: Write the failing test**

This test runs a real Keycloak in a container, so it is the module's slowest test; it stays a single scenario.

`modules/usermanagement/src/test/java/pl/najem/um/adapter/keycloak/KeycloakAdminAdapterTest.java`:

```java
package pl.najem.um.adapter.keycloak;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class KeycloakAdminAdapterTest {

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
        .withCommand("start-dev")
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/realms/master").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(3));

    static KeycloakAdminAdapter adapter;

    @BeforeAll
    static void setUp() {
        String baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        // The master realm and its admin-cli client exist out of the box; Phase 1 provisions into master
        // for tests, while docker-compose (Task 11) imports the dedicated "najem" realm.
        adapter = new KeycloakAdminAdapter(baseUrl, "master", "admin", "admin");
    }

    @Test
    void provisioningIsIdempotentPerEmail() {
        var first = adapter.provision("provisioned@example.com");
        var second = adapter.provision("provisioned@example.com");

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*KeycloakAdminAdapterTest'`
Expected: FAIL — `KeycloakAdminAdapter` does not exist.

- [ ] **Step 3: Write the adapter**

`KeycloakAdminAdapter.java`:

```java
package pl.najem.um.adapter.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import pl.najem.um.application.KeycloakAdminPort;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to the Keycloak admin REST API using the direct-grant admin login. Owns its wire DTOs
 * (convention 4) and never reads or writes roles or groups (decision D1).
 */
@Component
@ConditionalOnProperty("najem.keycloak.base-url")
public class KeycloakAdminAdapter implements KeycloakAdminPort {

    /** Wire DTOs — Keycloak's shapes, not the domain's. */
    record TokenResponse(String access_token) {}
    record UserRepresentation(String id, String username, String email, Boolean enabled) {}

    private final RestClient http;
    private final String realm;
    private final String username;
    private final String password;

    public KeycloakAdminAdapter(@Value("${najem.keycloak.base-url}") String baseUrl,
                                @Value("${najem.keycloak.realm:najem}") String realm,
                                @Value("${najem.keycloak.admin-username:admin}") String username,
                                @Value("${najem.keycloak.admin-password:admin}") String password) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
        this.realm = realm;
        this.username = username;
        this.password = password;
    }

    @Override
    public UUID provision(String email) {
        String token = adminToken();
        return findByEmail(token, email).orElseGet(() -> createUser(token, email));
    }

    private String adminToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", "admin-cli");
        form.add("username", username);
        form.add("password", password);
        var response = http.post()
            .uri("/realms/master/protocol/openid-connect/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
        if (response == null || response.access_token() == null) {
            throw new IllegalStateException("Keycloak admin login failed");
        }
        return response.access_token();
    }

    private java.util.Optional<UUID> findByEmail(String token, String email) {
        List<UserRepresentation> found = http.get()
            .uri(uriBuilder -> uriBuilder.path("/admin/realms/{realm}/users")
                .queryParam("email", email).queryParam("exact", true).build(realm))
            .header("Authorization", "Bearer " + token)
            .retrieve()
            .body(new org.springframework.core.ParameterizedTypeReference<List<UserRepresentation>>() {});
        if (found == null || found.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(UUID.fromString(found.getFirst().id()));
    }

    private UUID createUser(String token, String email) {
        http.post()
            .uri("/admin/realms/{realm}/users", realm)
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("username", email, "email", email, "enabled", true,
                "requiredActions", List.of("UPDATE_PASSWORD")))
            .retrieve()
            .toBodilessEntity();
        return findByEmail(token, email)
            .orElseThrow(() -> new IllegalStateException("Keycloak did not return the created user"));
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :modules:usermanagement:test --tests '*KeycloakAdminAdapterTest'`
Expected: PASS (1 test). First run pulls the Keycloak image and may take several minutes.

- [ ] **Step 5: Commit**

```bash
git add modules/usermanagement
git commit -m "Provision Keycloak users through an admin API adapter"
```

---

### Task 10: Resource-server security and REST adapters

**Files:**
- Create: `modules/usermanagement/src/main/java/pl/najem/um/adapter/security/SecurityConfig.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/adapter/security/CurrentUser.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/adapter/rest/WorkspaceController.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/adapter/rest/InvitationController.java`
- Create: `modules/usermanagement/src/main/java/pl/najem/um/adapter/rest/MemberController.java`
- Modify: `apps/najem-app/build.gradle.kts` (one dependency line)
- Test: `modules/usermanagement/src/test/java/pl/najem/um/adapter/security/CurrentUserTest.java`

**Interfaces:**
- Consumes: `WorkspaceService`, `InvitationService`, `MembershipService`, `WorkspaceAccess`, `UserService`.
- Produces:
  ```java
  public class CurrentUser {
      public CurrentUser(UserService users, WorkspaceAccess access);
      public UUID subject(Jwt jwt);                                  // parses the "sub" claim
      public UUID requireUserId(Jwt jwt);                            // 403 when the subject is unknown
      public void requireRole(Jwt jwt, UUID workspaceId, Role... allowed);
  }
  // REST:
  // POST   /api/um/workspaces                {name}                          -> {workspaceId}   (any authenticated user)
  // POST   /api/um/workspaces/{id}/invitations {email, role, expiresOn}      -> {invitationId, token}  (ADMIN)
  // DELETE /api/um/workspaces/{id}/invitations/{invitationId}                -> 204            (ADMIN)
  // POST   /api/um/invitations/accept        {token}                         -> {userId}       (public — the token is the credential)
  // GET    /api/um/me                                                        -> {userId, workspaces:[{workspaceId, role}]}
  // PUT    /api/um/workspaces/{id}/members/{userId}  {role}                  -> 204            (ADMIN)
  // DELETE /api/um/workspaces/{id}/members/{userId}                          -> 204            (ADMIN)
  ```

Security config lives in the module rather than the composition root so that `apps/najem-app` needs exactly one new line — the coordinator owns that file and a bigger diff there invites conflicts.

- [ ] **Step 1: Write the failing test**

`modules/usermanagement/src/test/java/pl/najem/um/adapter/security/CurrentUserTest.java`:

```java
package pl.najem.um.adapter.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.domain.Role;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserTest {

    private static Jwt jwtFor(UUID subject) {
        return Jwt.withTokenValue("token").header("alg", "none")
            .claim("sub", subject.toString())
            .issuedAt(Instant.EPOCH).expiresAt(Instant.MAX)
            .build();
    }

    @Test
    void resolvesTheSubjectClaim() {
        var subject = UUID.randomUUID();
        var currentUser = new CurrentUser(mock(UserService.class), mock(WorkspaceAccess.class));

        assertThat(currentUser.subject(jwtFor(subject))).isEqualTo(subject);
    }

    @Test
    void rejectsASubjectWithNoNajemUser() {
        var subject = UUID.randomUUID();
        var users = mock(UserService.class);
        when(users.findBySubject(subject)).thenReturn(Optional.empty());
        var currentUser = new CurrentUser(users, mock(WorkspaceAccess.class));

        assertThatThrownBy(() -> currentUser.requireUserId(jwtFor(subject)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void rejectsAMemberWithoutTheRequiredRole() {
        var subject = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var access = mock(WorkspaceAccess.class);
        when(access.roleIn(subject, workspaceId)).thenReturn(Optional.of(Role.VIEWER));
        var currentUser = new CurrentUser(mock(UserService.class), access);

        assertThatThrownBy(() -> currentUser.requireRole(jwtFor(subject), workspaceId, Role.ADMIN))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void allowsAMemberHoldingOneOfTheAllowedRoles() {
        var subject = UUID.randomUUID();
        var workspaceId = UUID.randomUUID();
        var access = mock(WorkspaceAccess.class);
        when(access.roleIn(subject, workspaceId)).thenReturn(Optional.of(Role.ADMIN));
        var currentUser = new CurrentUser(mock(UserService.class), access);

        currentUser.requireRole(jwtFor(subject), workspaceId, Role.ADMIN, Role.MANAGER);
        assertThat(access.roleIn(subject, workspaceId)).contains(Role.ADMIN);
    }
}
```

Add the test dependency `testImplementation("org.mockito:mockito-core")` to `modules/usermanagement/build.gradle.kts` (version managed by the Spring Boot BOM).

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:usermanagement:test --tests '*CurrentUserTest'`
Expected: FAIL — `CurrentUser` does not exist.

- [ ] **Step 3: Write CurrentUser**

`CurrentUser.java`:

```java
package pl.najem.um.adapter.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.domain.Role;

import java.util.Arrays;
import java.util.UUID;

/** Turns a JWT into NAJEM's own notion of who is calling. The token supplies a subject and nothing more (D1). */
@Component
public class CurrentUser {

    private final UserService users;
    private final WorkspaceAccess access;

    public CurrentUser(UserService users, WorkspaceAccess access) {
        this.users = users;
        this.access = access;
    }

    public UUID subject(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public UUID requireUserId(Jwt jwt) {
        return users.findBySubject(subject(jwt))
            .orElseThrow(() -> new AccessDeniedException("no NAJEM user for this subject"));
    }

    public void requireRole(Jwt jwt, UUID workspaceId, Role... allowed) {
        var role = access.roleIn(subject(jwt), workspaceId)
            .orElseThrow(() -> new AccessDeniedException("not a member of this workspace"));
        if (Arrays.stream(allowed).noneMatch(role::equals)) {
            throw new AccessDeniedException("role " + role + " may not perform this action");
        }
    }
}
```

- [ ] **Step 4: Write SecurityConfig**

`SecurityConfig.java`:

```java
package pl.najem.um.adapter.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server configuration. Enabled only when an issuer is configured, so Phase 0's e2e and
 * every module test keep running without an identity provider.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/um/invitations/accept").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}
```

- [ ] **Step 5: Write the REST adapters**

`WorkspaceController.java`:

```java
package pl.najem.um.adapter.rest;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/um")
public class WorkspaceController {

    public record CreateRequest(String name) {}

    private final WorkspaceService workspaces;
    private final WorkspaceAccess access;
    private final CurrentUser currentUser;

    public WorkspaceController(WorkspaceService workspaces, WorkspaceAccess access, CurrentUser currentUser) {
        this.workspaces = workspaces;
        this.access = access;
        this.currentUser = currentUser;
    }

    @PostMapping("/workspaces")
    public Map<String, UUID> create(@RequestBody CreateRequest request) {
        return Map.of("workspaceId", workspaces.create(request.name(), LocalDate.now()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        var subject = currentUser.subject(jwt);
        List<Map<String, Object>> workspaceList = access.forSubject(subject).stream()
            .map(m -> Map.<String, Object>of("workspaceId", m.workspaceId(), "role", m.role().name()))
            .toList();
        return Map.of("userId", currentUser.requireUserId(jwt), "workspaces", workspaceList);
    }
}
```

Remove the unused `BearerTokenAccessDeniedHandler` import if your IDE flags it — it is not needed.

`InvitationController.java`:

```java
package pl.najem.um.adapter.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.InvitationService;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/um")
public class InvitationController {

    public record InviteRequest(String email, Role role, LocalDate expiresOn) {}
    public record AcceptRequest(String token) {}

    private final InvitationService invitations;
    private final CurrentUser currentUser;

    public InvitationController(InvitationService invitations, CurrentUser currentUser) {
        this.invitations = invitations;
        this.currentUser = currentUser;
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public Map<String, Object> invite(@PathVariable UUID workspaceId,
                                      @RequestBody InviteRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        currentUser.requireRole(jwt, workspaceId, Role.ADMIN);
        var issued = invitations.invite(workspaceId, request.email(), request.role(),
            currentUser.requireUserId(jwt), LocalDate.now(), request.expiresOn());
        return Map.of("invitationId", issued.invitationId(), "token", issued.token());
    }

    @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID workspaceId, @PathVariable UUID invitationId,
                                       @AuthenticationPrincipal Jwt jwt) {
        currentUser.requireRole(jwt, workspaceId, Role.ADMIN);
        invitations.revoke(workspaceId, invitationId, LocalDate.now());
        return ResponseEntity.noContent().build();
    }

    /** Public by design: the invitation token IS the credential (D4). */
    @PostMapping("/invitations/accept")
    public Map<String, UUID> accept(@RequestBody AcceptRequest request) {
        return Map.of("userId", invitations.accept(request.token(), LocalDate.now()));
    }
}
```

`MemberController.java`:

```java
package pl.najem.um.adapter.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.MembershipService;
import pl.najem.um.domain.Role;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/um/workspaces/{workspaceId}/members")
public class MemberController {

    public record RoleRequest(Role role) {}

    private final MembershipService memberships;
    private final CurrentUser currentUser;

    public MemberController(MembershipService memberships, CurrentUser currentUser) {
        this.memberships = memberships;
        this.currentUser = currentUser;
    }

    @PutMapping("/{userId}")
    public ResponseEntity<Void> changeRole(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                                           @RequestBody RoleRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        currentUser.requireRole(jwt, workspaceId, Role.ADMIN);
        memberships.changeRole(workspaceId, userId, request.role(), LocalDate.now());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable UUID workspaceId, @PathVariable UUID userId,
                                       @AuthenticationPrincipal Jwt jwt) {
        currentUser.requireRole(jwt, workspaceId, Role.ADMIN);
        memberships.remove(workspaceId, userId, LocalDate.now());
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 6: Wire the module into the app**

In `apps/najem-app/build.gradle.kts`, add one line to `dependencies`, after the accounting line:

```kotlin
    implementation(project(":modules:usermanagement"))
```

- [ ] **Step 7: Run the module tests and the full build**

Run: `./gradlew :modules:usermanagement:test`
Expected: PASS.

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — the Phase 0 `WalkingSkeletonTest` must still pass. It has no issuer configured, so `SecurityConfig` stays inactive and no endpoint is secured.

- [ ] **Step 8: Commit**

```bash
git add modules/usermanagement apps/najem-app/build.gradle.kts
git commit -m "Expose workspace, invitation and member endpoints behind JWT authentication"
```

---

### Task 11: Keycloak in docker-compose and run documentation

**Files:**
- Modify: `docker-compose.yml`
- Create: `docker/keycloak/najem-realm.json`
- Modify: `apps/najem-app/src/main/resources/application.yml`
- Modify: `README.md`

**Interfaces:** Produces the local runtime: Keycloak on `http://localhost:8180`, realm `najem`, public client `najem-app`, and the app configured as a resource server against it.

- [ ] **Step 1: Add Keycloak to docker-compose**

Append to `docker-compose.yml`:

```yaml
  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: admin
      KC_HTTP_PORT: 8180
    volumes:
      - ./docker/keycloak:/opt/keycloak/data/import:ro
    ports:
      - "8180:8180"
```

- [ ] **Step 2: Add the realm import**

`docker/keycloak/najem-realm.json`:

```json
{
  "realm": "najem",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "clients": [
    {
      "clientId": "najem-app",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "redirectUris": ["http://localhost:8080/*"],
      "webOrigins": ["http://localhost:8080"]
    }
  ]
}
```

`"registrationAllowed": false` is the invite-only ruling expressed in the realm itself: Keycloak's own self-registration page is off, so `InvitationService.accept` is the only path that creates a user.

- [ ] **Step 3: Point the app at Keycloak**

Add to `apps/najem-app/src/main/resources/application.yml`, at the top level:

```yaml
najem:
  keycloak:
    base-url: "http://localhost:8180"
    realm: "najem"
    admin-username: "admin"
    admin-password: "admin"
```

Merge the `najem:` block with the existing one (which already holds `bank:`) rather than adding a second `najem:` key — a duplicate key silently discards the first.

Leave `spring.security.oauth2.resourceserver.jwt.issuer-uri` **unset** in `application.yml` so tests and the walking skeleton keep running unsecured. Document the opt-in in the README instead:

```
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://localhost:8180/realms/najem
```

- [ ] **Step 4: Document it**

Add a "User management" section to `README.md` covering: `docker compose up -d` now starts Keycloak too; the admin console at `http://localhost:8180` (admin/admin); that NAJEM users are created only by accepting an invitation; the curl sequence:

```bash
# 1. create a workspace (unsecured local run)
curl -s -X POST localhost:8080/api/um/workspaces \
  -H 'Content-Type: application/json' -d '{"name":"Agencja Krakowska"}'

# 2. invite a manager (returns the one-time token)
curl -s -X POST localhost:8080/api/um/workspaces/$WORKSPACE_ID/invitations \
  -H 'Content-Type: application/json' \
  -d '{"email":"manager@example.com","role":"MANAGER","expiresOn":"2026-09-01"}'

# 3. accept it — this is the only way a user is created
curl -s -X POST localhost:8080/api/um/invitations/accept \
  -H 'Content-Type: application/json' -d "{\"token\":\"$TOKEN\"}"
```

- [ ] **Step 5: Verify the compose file starts**

Run: `docker compose up -d keycloak && sleep 30 && curl -s -o /dev/null -w '%{http_code}' localhost:8180/realms/najem`
Expected: `200`. Then `docker compose down keycloak`.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml docker/keycloak README.md apps/najem-app/src/main/resources/application.yml
git commit -m "Run Keycloak locally with an invite-only najem realm"
```

---

### Task 12: End-to-end invite and access scenario

**Files:**
- Create: `e2e/src/test/java/pl/najem/e2e/InviteAndAccessTest.java`
- Modify: `e2e/build.gradle.kts` (add `testImplementation(project(":modules:usermanagement"))` if the e2e module needs the `Role` enum; otherwise send role names as strings and skip this)

**Interfaces:** Consumes the REST endpoints from Task 10 exactly as specified there.

Per roadmap rule 4, each phase appends scenarios to `e2e/`. This one proves the invite-only rule end to end without an identity provider running: the accept endpoint is public by design, and the module's Keycloak adapter is inactive when `najem.keycloak.base-url` is unset — so the test wires a stub port through a test configuration.

- [ ] **Step 1: Write the test**

`e2e/src/test/java/pl/najem/e2e/InviteAndAccessTest.java`:

```java
package pl.najem.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.app.NajemApplication;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InviteAndAccessTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static ConfigurableApplicationContext app;

    @BeforeAll
    static void start() {
        app = new SpringApplicationBuilder(NajemApplication.class, StubKeycloakConfig.class).properties(
            "server.port=0",
            "spring.datasource.url=" + pg.getJdbcUrl(),
            "spring.datasource.username=" + pg.getUsername(),
            "spring.datasource.password=" + pg.getPassword()).run();
        RestAssured.port = app.getEnvironment().getProperty("local.server.port", Integer.class);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class StubKeycloakConfig {
        @org.springframework.context.annotation.Bean
        pl.najem.um.application.KeycloakAdminPort keycloakAdminPort() {
            var subjects = new java.util.concurrent.ConcurrentHashMap<String, UUID>();
            return email -> subjects.computeIfAbsent(email, e -> UUID.randomUUID());
        }
    }

    @Test
    void invitedManagerJoinsTheWorkspaceAndNobodyElseDoes() {
        String workspaceId = given().contentType(JSON).body(Map.of("name", "Agencja E2E"))
            .post("/api/um/workspaces").then().statusCode(200).extract().path("workspaceId");

        var invite = given().contentType(JSON)
            .body(Map.of("email", "manager@example.com", "role", "MANAGER", "expiresOn", "2026-12-31"))
            .post("/api/um/workspaces/" + workspaceId + "/invitations")
            .then().statusCode(200).extract();
        String token = invite.path("token");

        String userId = given().contentType(JSON).body(Map.of("token", token))
            .post("/api/um/invitations/accept").then().statusCode(200).extract().path("userId");

        assertThat(userId).isNotBlank();

        // the same token cannot be replayed
        given().contentType(JSON).body(Map.of("token", token))
            .post("/api/um/invitations/accept").then().statusCode(500);

        // an unknown token never creates a user
        given().contentType(JSON).body(Map.of("token", "fabricated-token"))
            .post("/api/um/invitations/accept").then().statusCode(500);
    }
}
```

The `500`s are honest about today's behaviour: `IllegalStateException` is unmapped. If a later task adds an exception handler mapping it to `409`, update these two assertions with it — do not add the handler here, it belongs with the REST adapters.

- [ ] **Step 2: Run it and confirm it passes**

Run: `./gradlew :e2e:test --tests '*InviteAndAccessTest'`
Expected: PASS.

- [ ] **Step 3: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — `WalkingSkeletonTest` included.

- [ ] **Step 4: Commit**

```bash
git add e2e
git commit -m "Prove invite-only workspace access end to end"
```

- [ ] **Step 5: Post STATUS done**

Post to najem-build: STATUS najem-usermgmt — DONE, branch `najem-usermgmt/phase1-usermanagement`, commit hash, `./gradlew build` green, request review.

---

## Questions raised by this plan — both ANSWERED

Kept for the record: these were posted as HUMAN-QUESTIONs (najem-build seq 40) and ruled plan-level by the
coordinator at seq 56, from the domain-session records rather than by asking the human again.

**1. Workspace role set — ANSWERED: ADMIN + MANAGER only.** The proposal was four roles
(ADMIN/MANAGER/OWNER/VIEWER) plus two questions: whether an agency needs a distinct ACCOUNTANT, and
whether OWNER should be restricted to specific properties in Phase 1. Ruling: the property OWNER is
**not a system user in MVP**, and owner statements / tax packs were cut from scope — so no OWNER, no
VIEWER, no ACCOUNTANT. Per-property grants are **not** Phase 1: membership stays workspace-level and
access arrives later as an access-layer filter. See decision D5; the enum stays extensible.

**2. First-admin bootstrap — ANSWERED: the recommended default was adopted.** Invite-only left two
holes: (i) a newly created workspace has no members, so nobody can invite into it, and (ii) a fresh
deployment has no first user at all. Both are closed — (i) by the creator becoming ADMIN inside
`WorkspaceService.create` (decision D7), (ii) by the config-seeded `PlatformOperator` (decision D8).

## As built — where the code deviates from the tasks above

The tasks below were written before implementation and are kept as the record of intent. Four things
came out differently, and the code is the truth:

1. **`SecurityConfig` is unconditional, with two branches** — not `@ConditionalOnProperty`. This module
   puts spring-security on the whole application's classpath, so with no chain registered Boot's default
   would demand authentication on **every endpoint in the monolith**, including other modules'. The bean
   now always registers: permit-all when no issuer is configured (identical to pre-module behaviour),
   resource-server when one is.
2. **`WalkingSkeletonTest` needed three more `spring.autoconfigure.exclude` entries.** e2e runs FakeBank
   and najem-app in one JVM on one classpath, so FakeBank inherited security it has no use for and 401'd
   the seed call. The exclusion sits where the classpath sharing happens; adding spring-security to
   `apps/fakebank` was considered and rejected as polluting a module for a test-JVM artefact.
3. **`KeycloakAdminAdapter` is built by `IdentityProviderConfig`, not annotated `@Component`.** The port
   must always exist or `InvitationService` cannot be constructed and the application will not start
   without Keycloak configured. The factory returns the real adapter when `najem.keycloak.base-url` is
   set and otherwise one that **throws on use** — the context starts, provisioning fails loudly rather
   than inventing accounts.
4. **`PlatformOperator` and `WorkspaceCaller` are not in the task list at all.** They are decisions D7/D8
   made concrete. `WorkspaceCaller` resolves the acting user — JWT when secured, configured operator when
   not, `AccessDeniedException` when neither, so an unauthenticated request never ends up acting as
   somebody.

Also: `KeycloakAdminAdapterTest` is `@Tag("keycloak")` and excluded from the default build — it pulls and
boots a real identity provider, which took the full build from ~30s to 21m47s. Opt in with
`-PkeycloakTests`; CI runs it always (GitHub Actions sets `CI` itself). The trade is real and stated on
the class: locally, nothing then proves the adapter speaks Keycloak's actual API.

## Self-Review

**1. Spec coverage.** Roadmap Phase 1 scope for this module is "UserManagement (Keycloak, workspaces)" plus the coordinator's seq 21 pt 6 ("Workspace aggregate + membership/roles + INVITE-ONLY provisioning; WorkspaceCreated as an integration event"). Mapping: Keycloak → Tasks 9, 11 (+ D1 enforced in Tasks 7, 8, 10); workspaces → Tasks 2, 4; membership/roles → Tasks 7, 8; invite-only → Tasks 6, 7, 11 step 2, 12; `WorkspaceCreatedEvent` → Task 3, emitted in Task 4; access enforcement (seq 21 pt 8) → Tasks 8, 10; `UserId ↔ ContactId` (seq 14 settlement) → Task 5. Flyway range, no-PII, no-contracts-edit, no-`git add -A` → Global Constraints, exercised by assertions in Tasks 5 and 6. e2e continuity (roadmap rule 4) → Task 12. Nothing in the module's scope is unassigned.

**2. Placeholder scan.** No TBDs. Every step carries the actual SQL, Java, YAML, JSON or command. The one deliberate deferral — the role set — is a stated default with a written-out question, not a gap: Task 1 ships `Role` with four constants regardless of the answer.

**3. Type consistency.** `Role` (Task 1) is used identically in Tasks 6, 7, 8, 10. `InvitationService.Issued(invitationId, token)` returned in Task 6 is consumed in Tasks 7, 8, 12. `InvitationService`'s constructor gains two arguments in Task 7 — Task 7 step 6 explicitly updates Task 6's test rather than leaving it broken. `KeycloakAdminPort.provision(String) -> UUID` is defined in Task 7 and implemented in Task 9 with the same signature; the e2e stub in Task 12 matches it as a lambda. `UserService.findBySubject` returns `Optional<UUID>` in Task 5 and is used as such in Tasks 7 and 10. `WorkspaceAccess.Membership(workspaceId, role)` is defined in Task 8 and consumed in Task 10's `/api/um/me`. `WorkspaceCreatedEvent(workspaceId, name)` is identical in Tasks 3 and 4. Table and column names match between the migration (Task 1) and every query that follows.

**4. Known rough edge, stated rather than hidden.** `WorkspaceService.create` lets any authenticated caller create a workspace and does not make the creator its first ADMIN — so a freshly created workspace has no members and nobody can invite into it. Task 12's e2e works because the invite endpoint's `requireRole` check is inert while security is disabled. Closing this properly means "creator becomes ADMIN", which needs a `UserId` for the creator, which needs an authenticated session — i.e. it depends on how the first user of a brand-new deployment comes into being (bootstrap admin). That is a real design question, not an oversight; it is raised with the coordinator alongside the role-set question and closed in a follow-up task once answered.
