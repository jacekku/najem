# Frontend (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **STATUS: AWAITING COORDINATOR GATE.** Assigned to **najem-frontend** at najem-build seq 85, confirmed seq 91. Worktree `../najem-wt/najem-frontend`, branch `najem-frontend/phase2-frontend`. **No Flyway range — deliberately.** Decisions A–D below are proposals until ruled; Decision B in particular asks the coordinator to invert the briefed screen order.

**Goal:** Give NAJEM its first human interface — HTMX + Thymeleaf fragments served by the monolith — starting with the workspace seam every screen depends on, then the reconciliation screen, then the unit and arrears boards as their read sides arrive.

**Architecture:** Server-rendered fragments. No SPA, no JS framework, no client-side state. A request resolves a workspace, calls modules' application services in-process, and renders HTML. HTMX supplies partial updates over the same endpoints.

**Tech stack:** Java 21, Spring Boot 3.3 (`spring-boot-starter-thymeleaf`, `spring-boot-starter-web`), HTMX (vendored, not CDN — see Decision E), JUnit 5 + AssertJ + `MockMvc`, Testcontainers Postgres 16 for the integration slice.

## Global constraints

- Build env exports (`JAVA_HOME`, colima `DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`), no `git add -A`, worktree-per-agent, `./gradlew build` as the gate, branch `<agent>/<task>` — coordinator's standing conventions.
- **`./gradlew build` skips the Keycloak suite** (`@Tag("keycloak")`). Everything here is security-facing by nature, so the gate for this module is **`./gradlew build -PkeycloakTests`**, not plain `build`. Relayed by najem-contacts at seq 96 from najem-usermgmt's seq 63.
- **No Flyway range, and the absence is load-bearing** (coordinator seq 91). If a task here wants a migration, that means a screen is storing state a module should own — **stop and post on najem-build**, do not invent a range.
- Every screen is workspace-scoped. Templates **receive** a workspace; they never resolve one.
- **Templates never recompute a domain rule.** Arrears colour comes from `/api/acc/board` verbatim; unit and tenancy state come from PM's read side. If a screen needs a number no API exposes, request the endpoint on najem-build (seq 85 hard rule).
- Rebase → **re-run the full build** → push. Not the other way round (najem-contacts' self-reported breach, seq 94).
- Frontend introduces **no** integration events and needs no CCR.

## What actually exists — read-side survey, 2026-08-04

I read every controller rather than planning against assumed endpoints. Posted as seq 99. The **entire** monolith read surface is eight endpoints:

| Endpoint | Returns | Workspace source |
|---|---|---|
| `GET /api/acc/board` | `{tenancyId, status}`, status ∈ {`awaiting`, `green`} | `X-Workspace-Id` header → `DEV_WORKSPACE_ID` |
| `GET /api/acc/suggestions` | `{paymentId, chargeId}` | same |
| `GET /api/acc/warnings` | `{warningId, tenancyId, kind, detail}` | same |
| `GET /api/um/me` | `{userId, workspaces:[{workspaceId, role}]}` | JWT subject → `WorkspaceAccess.forSubject` |
| `GET /api/contacts/{contactId}` | `ContactDetails` | `X-Workspace-Id` header → `DEV_WORKSPACE_ID` |
| `GET /api/contacts?email=` | `[contactId]` | same |
| `GET /api/contacts/units/{unitId}/interests` | interests | same |
| `GET /api/contacts/erasure-due` | contacts due erasure | same |

Two consequences the briefed screen order does not survive contact with:

1. **`modules/propertymanagement` has zero GET endpoints.** Every mapping in `PortfolioController`, `TenancyController` and `ChecklistController` is a `@PostMapping`. **The Unit Board — briefed as "THE central screen" — has no read side to render from.** Gated on najem-pm (task 8 of 16 at time of writing).
2. **The arrears board is thinner than briefed.** `/api/acc/board` serves the Phase 0 walking-skeleton pair `awaiting`/`green`, keyed **per tenancy**. The five-colour scale (golden/green/yellow/red/bright-red) does not exist yet, and there is no unit or property on the response to group by. Gated on najem-accounting, whose allocation engine is the ranked priority.

Screen 3's endpoints all exist. The workspace seam is unblocked by `/api/um/me`. **The only screens I can start are the ones the briefed order puts last.**

---

## Decision A — the UI lives in `apps/najem-app`, not a new `modules/web`

The coordinator asked this be argued rather than picked (seq 85). The tension as posed: a view module reads four modules' read sides, which strains "modules never depend on each other".

**The tension dissolves once you ask *which* rule `modules/web` would be obeying.** Roadmap rule 2 binds **modules**. `apps/najem-app` is not a module — it is the composition root, and it already declares `implementation(project(...))` on all four modules plus `contracts` and `platform:eventstore`. Knowing every module is the composition root's entire job. Putting the UI there adds no dependency that does not already exist and breaks no rule.

`modules/web` has exactly two possible shapes, and both are worse:

- **In-process:** it needs `implementation` dependencies on four modules. That is a module depending on modules — the isolation rule broken outright, and broken in a Gradle-enforceable way that the constraint checks should reject. To permit it we would have to carve a second privileged exception a month after carving Reporting's, which is how a rule stops being a rule.
- **Over HTTP, calling its own process:** no compile dependency, so the rule survives on paper. But it buys that with a JSON round trip through the loopback for every fragment, and — decisively — **it inherits the `X-Workspace-Id` header seam.** Those endpoints resolve workspace from a header with a `DEV_WORKSPACE_ID` fallback, and `WorkspaceHeader`'s own javadoc calls itself a "Phase 1 stand-in for access control". A UI that authenticates a user and then asks the backend over HTTP has to *re-assert* the workspace as a header the callee trusts unconditionally. That is a tenancy boundary enforced by a request header the client sets — the thing the human ruled must be hard.

**In-process from the composition root is not merely allowed, it is the only option that lets the workspace stay resolved.** Every application service already takes an explicit `workspaceId` — `WorkspaceHeader`'s javadoc says so: *"the application services already take an explicit workspaceId"*, and the header classes exist only so that Phase 1 had something to pass. So the UI resolves the subject through `WorkspaceCaller`, resolves the workspace through `WorkspaceAccess`, and passes it explicitly to the service. **The dev header is never touched, and the boundary is enforced where the data is fetched rather than asserted by the caller.**

**Cost, stated honestly:** `apps/najem-app` grows from 3 classes to a UI. Mitigations, both binding on the tasks below: the UI lives in its own package `pl.najem.app.web` and touches nothing in `pl.najem.app` proper; and it calls **application services only** — never repositories, never `JdbcTemplate`, never another module's tables. If a controller here reaches for a `JdbcTemplate`, that is Decision A breaking and should fail review.

**Recommendation: `apps/najem-app`, package `pl.najem.app.web`.** I do not think this is a close call, so I am not asking the coordinator to rule between two live options — I am asking them to reject it if they disagree.

## Decision B — invert the screen order: seam first, then reconciliation

Briefed order (seq 85): 1. Unit Board · 2. Arrears board · 3. Reconciliation · 4. Login/workspace switching. The coordinator explicitly invited an argument for inverting (najem-usermgmt made the case before retiring).

**Three independent arguments converge, which is why I am asking for the inversion rather than noting it.**

1. **Boundaries retrofitted after the fact leak** (najem-contacts, seq 96 — a better framing than my own, adopted with credit). Workspace is not a field, it is a boundary. najem-contacts put `workspace_id` in the *first* Contacts migration precisely because a filter added later is one you must then prove you added everywhere. Query-enforced scoping is a **property** when it is there from the first query and a **change** when it isn't. Three screens built against a stubbed workspace are three screens where the scoping is a change.
2. **The seam does not exist yet and is nobody else's job.** `WorkspaceCaller` and `WorkspaceAccess` resolve a subject and their memberships, but **nothing hands a workspace to a view.** najem-usermgmt is retired; its screens are mine. Deferring the seam does not defer it to a specialist — it defers it to me, with three finished screens to retrofit.
3. **The briefed order's first two screens are blocked and its last two are not.** Screens 1 and 2 are gated on PM's and accounting's read sides. Screen 3 and the seam are buildable today. An order whose first task cannot start is not an order.

**And the compromise that removes the main objection** (najem-contacts, seq 96): the "nothing visible for longer" cost is real, but it applies to a polished *switcher UI*, not to the seam. **Split screen 4:**

- **4a — the seam.** Resolve subject → memberships → active workspace, thread it into a rendering context, pass it explicitly to every service call. Small, load-bearing, first. Needs only `/api/um/me`'s underlying services, which exist.
- **4b — the switcher UI.** A workspace picker. Needs nothing further now that `WorkspaceAccess.forSubject` is confirmed. Can land any time after 4a.

**Proposed order: 4a (seam) → 3 (reconciliation) → 4b (switcher) → 2 (arrears) → 1 (unit board)**, with 1 and 2 explicitly gated. Reconciliation comes second because it is the only screen whose data exists today, so it is what proves the seam against real data rather than a fixture.

**@najem-coordinator: this is the ruling I am asking for.** If it goes the other way, tasks 1–3 below are unchanged (they are the seam and the scaffold); tasks 4+ reorder and several become blocked-on-arrival.

## Decision C — how a workspace reaches a template

**Binding rule: a template receives a workspace; it never resolves one.** Concretely:

- A `WorkspaceContextFilter` (or `HandlerInterceptor`) resolves the acting subject via `WorkspaceCaller.resolveWithoutWorkspace(jwt)` and the active workspace via `WorkspaceAccess`, and puts a `WebWorkspace(workspaceId, role, userId)` on the request.
- Controllers take it as a resolved argument and pass `workspaceId` explicitly into application services.
- **Templates get data, never ids to look things up with.** No template ever calls a service, and no template receives a raw `JdbcTemplate`, repository, or module service in its model.
- **`X-Workspace-Id` is never set, read, or forwarded by any code in `pl.najem.app.web`.** A test asserts this by scanning the package — the dev header dying is najem-usermgmt's stated intent (*"the four `DEV_WORKSPACE_ID` seams can now be swapped"*), and the UI must not become a fifth caller that resurrects it.
- **Active-workspace selection:** when the subject has exactly one membership, that is the workspace. With several, the choice is held in the HTTP session and validated against `WorkspaceAccess.canAccess` **on every request** — a session value is a client-influenced input, not a credential.
- **A subject with zero memberships is not an error state to render around** — it is `AccessDeniedException`, matching `WorkspaceCaller`'s existing "an unauthenticated request must never end up acting as somebody".

## Decision D — `SecurityConfig` stays unconditional, and the frontend is the most likely thing to erode it

Recorded because the reasoning, not the rule, is what will keep this intact (najem-usermgmt seq 63/80, relayed seq 96).

Spring Security on the classpath **with no chain registered** does not mean "no security" — it means Boot's default chain: authenticate everything. So a `@ConditionalOnProperty` `SecurityConfig` that declines to register would silently secure PM's, accounting's and contacts' APIs, and the symptom lands in **someone else's e2e going 401**, not in ours.

**Why the frontend specifically is the risk:** in a no-issuer local run the permit-all branch looks like dead code. Every dev-loop convenience I add makes it look deader. The named failure mode: *"simplify SecurityConfig, the frontend works fine without it"* — true locally, breaks four modules in CI. Binding on this plan:

- `SecurityConfig` is not touched by any task here. If a task needs it changed, that is a stop-and-post.
- Any new UI route is added to the **existing** chain's rules, never by registering a second chain.
- Login-adjacent work runs `-PkeycloakTests` before push, not just when it feels security-shaped.

Also carried forward from seq 96, for whoever writes the first e2e: e2e runs FakeBank and najem-app in **one JVM on one classpath**, so the fix for inherited security is `spring.autoconfigure.exclude` entries where the sharing happens — *not* adding spring-security to `apps/fakebank`. And `SpringApplicationBuilder.properties(...)` registers **default** properties that `application.yml` then overrides, silently dialling `localhost:5432` instead of the container; use the `--arg` form.

## Decision E — HTMX is vendored, not loaded from a CDN

A CDN `<script>` makes every page render depend on a third party being up and on the network reaching it, and it puts an unpinned third-party script into an app handling tenancy-scoped financial data. Vendor `htmx.min.js` into `src/main/resources/static/vendor/`, pin the version in a comment with its SRI hash, and serve it from the monolith. No build-time JS toolchain, no npm, no bundler — that is the point of choosing HTMX.

---

## Gating — what is blocked, on whom, and what unblocks it

Recorded so no task below pretends to be startable when it is not. **Neither ask is a request to reprioritise; both agents have higher-value work and know about these.**

| Screen | Blocked on | Needs |
|---|---|---|
| 1 — Unit Board | najem-pm | GET read side: units in a workspace with property, name, current base rent, open/closed-to-rent; current + next upcoming tenancy per unit (`TenancyPeriodRegistered` on the `Unit` stream already carries `unitId`/`tenancyId`/`start`/`end`, so the calendar exists — the *query* does not); property enough to group and label |
| 2 — Arrears board | najem-accounting | the five-colour scale, **and unit + property on the board response**. Without them, composing per-unit colour means joining tenancy→unit in the UI — which is recomputing an accounting-owned rule by another name |

## Tasks

### Task 1 — Thymeleaf scaffold and the `pl.najem.app.web` package

- [ ] RED: `WebScaffoldTest` — `MockMvc` GET `/` returns 200 and HTML content type. Fails: no controller, no view resolver.
- [ ] Add `spring-boot-starter-thymeleaf` to `apps/najem-app/build.gradle.kts`.
- [ ] `pl.najem.app.web.HomeController` + `templates/layout.html` + `templates/home.html`.
- [ ] Vendor `htmx.min.js` under `static/vendor/` with version + SRI comment (Decision E).
- [ ] GREEN. Then `./gradlew build -PkeycloakTests` full green before push.

### Task 2 — the workspace seam (screen 4a)

- [ ] RED: `WebWorkspaceTest` — a request from a subject with one membership renders with that workspace; a subject with none gets `AccessDeniedException`; a session-held workspace the subject cannot access is rejected on **every** request, not just at selection.
- [ ] RED: `NoDevHeaderTest` — scan `pl.najem.app.web` sources/classes and assert the string `X-Workspace-Id` appears nowhere (Decision C).
- [ ] `WebWorkspace` record + `WorkspaceContextFilter` resolving via `WorkspaceCaller` / `WorkspaceAccess`.
- [ ] Controllers receive `WebWorkspace`; services are called with an explicit `workspaceId`.
- [ ] GREEN. Full build with `-PkeycloakTests`.

### Task 3 — reconciliation screen (screen 3)

- [ ] RED: renders the suggestion queue, unseen warnings, and marks a warning seen via HTMX POST; every query is workspace-scoped and a second workspace's rows are absent.
- [ ] Call `ReconciliationService` / `WarningService` **in-process** with the resolved workspace — not over HTTP, not via the dev header.
- [ ] Fragments: suggestion row, warning row. HTMX swaps the row, not the page.
- [ ] Bank-vs-app balance and ALARM, and suspense aging: **verify these are exposed before building them** — the survey found only `/suggestions` and `/warnings`. If the numbers are not there, post on najem-build and build the rest of the screen without them rather than computing them here.
- [ ] GREEN. Full build with `-PkeycloakTests`.

### Task 4 — workspace switcher (screen 4b)

- [ ] RED: a subject with two memberships sees both and can switch; switching re-scopes the reconciliation screen; a workspace the subject is not a member of cannot be selected even by a forged form post.
- [ ] Switcher fragment driven by `WorkspaceAccess.forSubject`.
- [ ] GREEN. Full build with `-PkeycloakTests`.

### Task 5 — arrears board (screen 2) — **GATED on najem-accounting**

- [ ] Do not start until the five-colour scale and unit/property are on the board response.
- [ ] RED: colours render **verbatim** from the API. A test asserts the UI has no colour-deriving logic — no thresholds, no date arithmetic, no mapping from amounts to colours.
- [ ] Per-unit and per-property grouping from the API's own fields.

### Task 6 — Unit Board (screen 1) — **GATED on najem-pm**

- [ ] Do not start until PM exposes the read side in the gating table.
- [ ] RED: searchable unit list, status, anchor rent, current and upcoming tenancy; workspace-scoped.
- [ ] Contact display names composed here (settled seq 31 pt 3 — this is the frontend's job, and Reporting deliberately consumes nothing from Contacts).

### Task 7 — e2e

- [ ] Append one scenario per landed screen to `e2e/`, per roadmap rule 4.
- [ ] Heed the two traps in Decision D: `spring.autoconfigure.exclude` for the shared-JVM classpath, and `--arg` rather than `SpringApplicationBuilder.properties(...)`.

## Verification

Every task: red first, **observed** red (not assumed), then green, then `./gradlew build -PkeycloakTests --rerun-tasks` before push. `--rerun-tasks` because merging over other agents' fresh commits makes a cached green meaningless — najem-integrations' habit (seq 98), and najem-reporting's Flyway case is why it matters: *a test that has never been observed to fail is indistinguishable from a test that cannot fail*, and worse, a green can be produced by machinery that never ran.

For the two "the UI must not do this" tests (`NoDevHeaderTest`, and the arrears no-colour-logic test), the red step must be **observed by writing the violation**, confirming the test fails, then removing it. A guard test that has never seen its violation is decoration.
