# Reporting Context (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **STATUS: IN BUILD.** Phase 2 gate OPENED by human directive (najem-build seq 73). Reporting is assigned to **najem-contacts/najem-reporting** with an early start granted (seq 65 pt 4), Flyway range V60–V69 claimed. Decisions 1 and 2 were ruled at seq 65 and are recorded below as rulings, not proposals — **read them as binding, not as the recommendations they started as.**

**Goal:** Build `modules/reporting` — the read-side context that answers "what happened to this property / unit / tenancy, and when" by projecting facts from Property Management and Accounting into the multi-level **Timeline**, the domain expert's flagship wish (`session-2026-08-03-property-management.md:201`).

**Architecture:** Pure projections. No aggregates, no commands, no domain events of its own — `property-management-domain-model.md:148` is explicit: "Pure projections (the Timeline family + payment status boards). No commands, no aggregates." A catch-up projector walks the shared `events` table by `global_seq`, maps rows into denormalised timeline tables, and REST serves them. Reporting is downstream of everyone and upstream of nobody.

**Tech Stack:** Java 21, Spring (spring-context / spring-tx / spring-jdbc / spring-web), Flyway, Jackson (reading `jsonb` payloads as trees, **not** as other modules' Java types), JUnit 5 + AssertJ + Testcontainers Postgres 16.

## Global Constraints

- Build env exports, testcontainers/api.version/-parameters prohibitions, no `git add -A`, worktree-per-agent, `./gradlew build` as the gate, branch `<agent>/<task>` — all per the coordinator's standing conventions (najem-build seq 15, 19, 23, 30).
- Flyway: `classpath:db/reporting`, **range V60–V69** (coordinator seq 19 pt 2). `application.yml` will need `classpath:db/reporting` appended — that is a composition-root file, so **coordinator-merged**, not self-merge.
- Every table carries `workspace_id uuid not null`; every query filters on it; cross-workspace reads do not exist (seq 21).
- Self-merge bound now firmly **one task per merge** (coordinator seq 56 pt 3). Anything larger goes READY-FOR-REVIEW.
- Reporting introduces **no** integration events and needs no CCR. It only reads.

## Decision 1 — RULED (seq 65 pt 2): privileged read-side, **restricted to an allowlist of streams**

**What was ruled.** Reporting reads the shared `events` table directly as `event_type` + Jackson `JsonNode` — no Gradle dependency on any module, no other module's record classes — **but only for streams on the allowlist below.** Every other stream is off limits and stays private to its owner. A tripwire test per consumed stream is **mandatory**, not optional.

| Owner | Readable streams | Off limits |
|---|---|---|
| Property Management | `Property`, `Unit`, `Tenancy` | everything else |
| Accounting | `TenancyLedger`, `Payment` | everything else |
| Contacts | all (PII-free by construction — the lookaside is the whole design) | — |
| User Management | `Workspace`, `User` | its other six domain events |

**The allowlist is @najem-usermgmt's amendment (seq 64) and it is a strict improvement on what this plan originally proposed.** My original text asked for the table wholesale, on the argument that a tripwire test would make the coupling "visible and monitored rather than latent". That argument was too weak for the thing it was defending: a monitor tells you after the fact, and wholesale access would have made *every* module's private events a published interface by accident — including UserManagement's invitation lifecycle, which is the one place in that module adjacent to PII. Default-private with a named allowlist is correct-by-construction where it matters and cheap everywhere else.

**Consequences that bind the tasks below:**
- A projection may only handle event types that live on an allowlisted stream. If a Timeline lane wants a fact from a private stream, the answer is to **ask the owning agent for a read model or an integration event** — never to widen the read.
- Owners have committed to posting on `najem-build` before changing payloads on allowlisted streams (@najem-usermgmt seq 66, @najem-pm seq 67). The tripwire is the second line, not the first.
- **@najem-usermgmt seq 66, pinned here because it is a trap:** the invitation lifecycle (`MemberInvited` / `InvitationAccepted` / `InvitationRevoked` / `MemberRoleChanged` / `MemberRemoved`) rides the `Workspace` stream, and **the invitee's email is deliberately not in the payload** — it lives in a lookaside. A Timeline that wants to render "X invited Y" **cannot** recover Y from the event and must request a read model. Do not reach into the payload for it; it is not there, and the absence is the design working.

## Decision 1 (original recommendation, superseded — kept for the record)

Reporting needs PM facts (tenancy reserved/activated/ended, repairs, inspections) *and* Accounting facts (charge posted, payment allocated, arrears). Roadmap rule 2 says modules depend only on `contracts` + `platform` and talk via integration events. Taken literally, every fact the Timeline shows must first become an integration event in `contracts/` — roughly **20+ new records**, each needing a CCR, each coupling PM's and Accounting's release cadence to Reporting's appetite for detail. That is a large, permanent tax on a context that only reads.

The domain docs point the other way. `property-management-domain-model.md:133` says "**All events → Reporting projections**", and `:149` says Reporting "Consumes PM + Accounting streams" — streams, not a curated event subset.

**Recommendation: sanction Reporting as a privileged read-side that reads the shared `events` table directly, and write that exception into the roadmap rather than letting it happen quietly.** Concretely:

- Reporting gets **no** Gradle dependency on `modules:propertymanagement` or `modules:accounting` — the isolation that matters (no compile coupling, no shared types, no cross-module method calls) is preserved and stays Gradle-enforceable.
- It reads `events` rows as **`event_type` string + Jackson `JsonNode` payload**, never deserializing into another module's record class. A module renaming an internal field breaks a Reporting *test*, not a compile, and never breaks the module that changed.
- Task 3 builds an explicit **event contract test** that fails loudly and by name when an expected `event_type` or field disappears — so the coupling is visible and monitored rather than latent.
- Reporting never writes to another module's tables and never reads them. Only `events`.

**The honest cost:** other modules' internal event payloads become a de-facto published interface for Reporting, and their authors will not know it from their own code. The contract test is the mitigation, not a cure — it tells us *after* a change, in Reporting's suite. Alternative if the coordinator judges that unacceptable: the integration-event route, which is stricter and correct-by-construction but costs 20+ CCRs and makes every new Timeline lane a cross-agent negotiation. **This is a cross-cutting architectural call with irreversible schema consequences — coordinator's ruling needed, escalate to the human if you judge it human-level (seq 38).** Tasks 2 onward assume the recommendation; if the ruling goes the other way, Tasks 3–7 change shape but Task 1 does not.

## Decision 2 — Arrears board colours are Accounting's, not Reporting's

The roadmap's Phase 2 line reads "Reporting (Timeline projections, **arrears board full colors**)". That collides with `najem-accounting`'s plan Task 11, which builds exactly that, and their seq 36 decision 2 already made colour a recomputed projection they own. **Two modules must not both compute a legal-consequence colour** — `bright red` feeds the `ArrearsReached3Periods` counter behind art. 11 termination rights, and a divergence between two implementations is a wrong-eviction risk.

**RULED (seq 65 pt 3): accounting keeps colour**, `/api/acc/board` is the single source, the roadmap line is amended, and @najem-accounting confirmed at seq 74. Reporting composes payment facts into the Timeline storyline only. The original proposal, which the ruling adopted:

 and its `/api/acc/board` endpoint, unchanged. Reporting consumes payment/charge facts for the **Timeline's** "invoice generated but unpaid (red)" storyline only, which is a per-tenancy narrative, not the board. Where a screen needs both, the **frontend composes** two endpoints — the same rule already settled for contact display names (seq 31 pt 3, approved seq 28). The roadmap line should be amended to drop "arrears board full colors" from Reporting's scope. @najem-accounting should confirm; @najem-coordinator to amend the roadmap.

## Decision 3 — Projection catch-up, not outbox subscription

The existing `OutboxDispatcher` delivers *integration* events by direct handler invocation. Reporting instead runs a **catch-up projector**: a `reporting_checkpoint(projection_name, last_global_seq)` row per projection, a `@Scheduled` sweep that reads `events where global_seq > checkpoint order by global_seq limit N`, applies handlers, and advances the checkpoint in the same transaction. Rationale:

- **Rebuildable.** Set the checkpoint to 0 and a projection rebuilds itself from history. That is the property that makes a read model safe to change — new Timeline lane, no migration, just a replay. An outbox-subscription model cannot do this: the outbox is drained.
- **Restart-safe and idempotent** by construction (checkpoint advances transactionally with the writes).
- **Ordering** is `global_seq`, a single monotonic sequence across all streams — which is exactly what a multi-stream Timeline needs and what per-stream versions cannot give.

Cost: polling latency (a second or two), and `global_seq` gaps from rolled-back transactions are permanent — the projector must treat gaps as normal and never wait for a missing seq.

## Decision 4 — Curated, not exhaustive

`session-2026-08-03-property-management.md:206`: "Major events curated (not every event shown)." The Timeline is a story a human reads, not an audit log. Each projection declares an explicit allowlist of event types it renders; anything unrecognised is skipped silently (and counted, so "we're ignoring 4000 events of a type nobody mapped" is visible in a metric rather than invisible). Audit needs are already served by the event store itself.

## Decision 5 — `error-annulled` tenancies are excluded from occupancy, included in the unit timeline

`property-management-domain-model.md:63`: "reporting excludes phantom tenancies from occupancy stats". But a mistaken activation that was annulled *did* happen to that unit, and a manager looking at the unit's history should see it — silently vanishing records is how people lose trust in a timeline. So: occupancy percentages exclude them; the unit-level timeline shows them marked as annulled. Both behaviours get a test.

## File Structure

- `modules/reporting/build.gradle.kts` — deps: `platform:eventstore` (for `JdbcEventStore`'s schema only — no module deps), spring-jdbc/tx/web/context, jackson-databind.
- `settings.gradle.kts` + `apps/najem-app/build.gradle.kts` + `application.yml` — **coordinator-merged** (three shared files; 2c only covers the single app-build line).
- `src/main/resources/db/reporting/V60__reporting.sql` — checkpoint + timeline + occupancy tables.
- `application/EventFeed.java` — reads `events` by `global_seq` as `(seq, streamId, streamType, eventType, JsonNode)`.
- `application/Projection.java` — SPI: `name()`, `handles()`, `apply(FeedEntry)`.
- `application/ProjectionRunner.java` — checkpointed catch-up loop + `@Scheduled` trigger + `rebuild(name)`.
- `application/TenancyTimelineProjection.java`, `UnitTimelineProjection.java`, `PropertyOccupancyProjection.java`.
- `application/TimelineQuery.java` — read side for REST.
- `adapter/rest/TimelineController.java`, `OccupancyController.java`.
- Tests mirroring each, plus `EventContractTest` (Decision 1's tripwire).

---

### Task 1: Module scaffold, schema, and the projector's checkpoint

**Files:** `modules/reporting/build.gradle.kts` · `V60__reporting.sql` · `EventFeed.java` · `ReportingSchemaTest.java`, `EventFeedTest.java`

**Interfaces produced:** `EventFeed.since(long globalSeq, int limit) -> List<FeedEntry>` where `FeedEntry(long globalSeq, UUID streamId, String streamType, String eventType, JsonNode payload)`; tables `reporting_checkpoint`, `reporting_timeline_entry`, `reporting_unit_state`, `reporting_property_occupancy`.

- [ ] **Step 1: Write the failing test** — `EventFeedTest` inserts three rows straight into `events` (any event_type; the feed must not care), then asserts `since(0, 10)` returns them in `global_seq` order with payloads readable as `JsonNode`, and `since(secondSeq, 10)` returns only the third. Plus: an event type the feed has never seen is returned rather than rejected.
- [ ] **Step 2: Run to verify it fails** — `./gradlew :modules:reporting:test` → project does not exist.
- [ ] **Step 3: Build files, migration, `EventFeed`.** Migration sketch:
```sql
create table reporting_checkpoint (
  projection_name text primary key,
  last_global_seq bigint not null default 0
);

-- One row per curated fact on a timeline. Denormalised on purpose: a timeline
-- read is one indexed scan, and rebuilds make the duplication safe.
create table reporting_timeline_entry (
  entry_id     bigserial primary key,
  workspace_id uuid not null,
  level        text not null,          -- 'tenancy' | 'unit' | 'property'
  subject_id   uuid not null,          -- tenancyId | unitId | propertyId
  occurred_on  date not null,
  global_seq   bigint not null,        -- tiebreak + idempotency key
  kind         text not null,          -- 'tenancy-reserved', 'rent-paid', ...
  summary      text not null,
  detail       jsonb,
  unique (level, subject_id, global_seq)
);
create index reporting_timeline_by_subject
  on reporting_timeline_entry (workspace_id, level, subject_id, occurred_on, global_seq);
```
`unique (level, subject_id, global_seq)` is what makes replay idempotent — a re-run inserts the same rows and conflicts harmlessly rather than duplicating the story.
- [ ] **Step 4: Verify pass, then `./gradlew build` green.**
- [ ] **Step 5: Commit** — `git add modules/reporting` … `git commit -m "Add reporting module with an event feed and checkpoints"`. Shared-file edits go to the coordinator separately.

---

### Task 2: Checkpointed projection runner

**Files:** `Projection.java` · `ProjectionRunner.java` · `ProjectionRunnerTest.java`

**Interfaces produced:** `Projection { String name(); Set<String> handles(); void apply(FeedEntry entry); }` · `ProjectionRunner.runOnce() -> int applied` · `ProjectionRunner.rebuild(String projectionName)`.

- [ ] **Step 1: Failing tests** — four behaviours, each its own test: (a) a projection sees only the event types in `handles()`; (b) `runOnce()` twice applies each event exactly once (checkpoint advanced); (c) `rebuild(name)` resets the checkpoint and re-applies from zero, and the projection's own tables end up identical — the property that makes read models safe to evolve; (d) a **gap** in `global_seq` (delete a row to simulate a rolled-back transaction) does not stall the runner.
- [ ] **Step 2: Run — fails, no `ProjectionRunner`.**
- [ ] **Step 3: Implement.** Checkpoint read → `EventFeed.since` → dispatch to each projection whose `handles()` contains the type → advance checkpoint, all in one `@Transactional` unit. `@Scheduled(fixedDelay = 1000)` calls `runOnce()` (test drives `runOnce()` directly; no sleeping in tests).
- [ ] **Step 4: Verify pass.** — [ ] **Step 5: Commit.**

---

### Task 3: Event contract tripwires — one per consumed stream (MANDATORY per seq 65 pt 2)

**Files:** `EventContractTest.java` (test-only task — no production code)

This is Decision 1's mitigation and the reason it is a task rather than a footnote. The ruling makes it **mandatory and per-stream**: every allowlisted stream Reporting actually consumes gets its own tripwire, and a stream with no tripwire may not be consumed. The list Reporting reads (and therefore must pin) is `Property` · `Unit` · `Tenancy` · `TenancyLedger` · `Payment`, plus `Workspace`/`User` if and only if a Timeline lane ends up needing them — which as planned it does not, per the email trap in Decision 1.

- [ ] **Step 1:** Write a test enumerating every `(event_type, required field)` pair Reporting depends on — e.g. `pm.TenancyActivated` → `unitId`, `startDate`; `acc.PaymentAllocated` → `allocations[].tenancyId`. For each, drive the *owning module's own service* to emit one real event into a Testcontainers Postgres, then assert the field is present and non-null in the stored payload. Driving the real service (not a hand-written fixture) is the whole point: a hand-built JSON blob would keep passing after PM renamed the field.
- [ ] **Step 2:** Run it — it must pass on the current main, or the dependency list is already wrong.
- [ ] **Step 3:** Add a failure message naming the owning agent and the event: *"pm.TenancyActivated no longer carries `unitId` — Reporting's unit timeline depends on it; talk to najem-pm before changing this."* A tripwire nobody can interpret is a tripwire nobody fixes.
- [ ] **Step 4: Commit.**

> **Note for the reviewer:** this test needs `modules:propertymanagement` and `modules:accounting` on Reporting's **test** classpath only (`testImplementation`), never `implementation`. If the coordinator judges even a test-scope dependency a violation of rule 2, the fallback is asserting against committed golden JSON payloads — weaker, because it cannot notice a module changing its own emission.

---

### Task 4: Tenancy Timeline — the flagship

**Files:** `TenancyTimelineProjection.java` · `TenancyTimelineProjectionTest.java`

The domain's own example (`session-...-property-management.md:206`): "checklist done 5 days before keys handed over, moved in, deposit paid on date A, rent paid on date B, invoice generated but unpaid (shows red)."

**Curated allowlist (Decision 4):** `TenancyReserved` · `TenancyActivated` · checklist-completed · handover-protocol-recorded · `RentChangeApplied` · `TenancyEnded` (with reason) — from PM; `ChargePosted` · `PaymentAllocated` — from Accounting. Everything else skipped and counted.

- [ ] **Step 1: Failing test** — seed a full tenancy story across both modules' streams, run the projector, assert the timeline reads in occurrence order with the expected `kind`s. Then the one that matters: a charge posted with no allocation by its due date renders `kind = 'charge-overdue'`; once a `PaymentAllocated` covers it, a subsequent rebuild renders `charge-paid` instead. **Overdue is derived at read time from due date vs. allocation, never stored as a status** — a stored status would need a nightly job to age it and would be wrong between runs.
- [ ] **Step 2–5:** fail → implement → pass → commit.

---

### Task 5: Unit Timeline and unit state

**Files:** `UnitTimelineProjection.java` · `UnitTimelineProjectionTest.java`

"open X days → reserved → tenant Y months → repairs, problems" (`property-management-domain-model.md:123`).

- [ ] **Step 1: Failing tests** — (a) a unit's history renders reserved/occupied/vacant spans with correct day counts across two consecutive tenancies; (b) **a gap between tenancies produces a `vacant` span** (computed from the absence of coverage, not from an event — nothing emits "the unit went empty"); (c) an `error-annulled` tenancy appears in the timeline marked annulled (Decision 5); (d) repairs and inspections land on the unit's "problems" lane.
- [ ] **Step 2–5:** fail → implement → pass → commit.

---

### Task 6: Property occupancy

**Files:** `PropertyOccupancyProjection.java` · `PropertyOccupancyProjectionTest.java`

"how many units rented / available / unavailable" (`session-...-property-management.md:203`).

- [ ] **Step 1: Failing tests** — counts per property as of a date; **`error-annulled` tenancies excluded** (Decision 5) with a test asserting a property with one real and one annulled tenancy reports 1 occupied, not 2; a unit closed for renovation counts as unavailable, not available.
- [ ] **Step 2–5:** fail → implement → pass → commit.

> **Deliberately NOT here: rent-target-vs-actual.** `property-management-domain-model.md:222` lists it as open hotspot #11, "Concept captured, design later" — the read model's *shape* is undecided. PM's `RentTargetSet` gives the input, so this is one additive projection later. Building it now means inventing the shape and being wrong.

---

### Task 7: REST + e2e

**Files:** `TimelineController.java` · `OccupancyController.java` · `e2e/.../TimelineTest.java`

- `GET /api/reporting/tenancies/{id}/timeline` · `GET /api/reporting/units/{id}/timeline` · `GET /api/reporting/properties/{id}/occupancy?asOf=` · `POST /api/reporting/projections/{name}/rebuild` (operator action; `@Profile("!prod")` until someone decides who may rebuild).
- Workspace from `X-Workspace-Id` with the dev fallback — the same seam as the other four modules (`pm.WorkspaceHeader`, `contacts.WorkspaceContext`, `acc.WorkspaceContext`, usermgmt's). Reporting makes it five; it dies with the others when `WorkspaceAccess.roleIn(...)` lands.
- e2e: drive the **real** walking-skeleton flow (create property → unit → tenancy → activate → seed a FakeBank payment → ingest → confirm), then assert the tenancy timeline tells that story back. Awaitility on the projector's polling delay, matching `WalkingSkeletonTest`'s existing await style.
- New e2e **file** is self-mergeable (seq 30 pt 2d); the `application.yml` Flyway line is **not** — coordinator merges that.

---

## Questions asked, and how they were answered

Kept rather than deleted, so the trail from question to ruling survives for whoever reads this next.

1. **Decision 1 — direct `events` read vs. integration-events-only.** ANSWERED (seq 65 pt 2): privileged read-side **with a per-stream allowlist**, tripwire per consumed stream mandatory. See the rewritten Decision 1.
2. **Decision 2 — arrears colours.** ANSWERED (seq 65 pt 3): accounting's, roadmap amended, @najem-accounting confirmed at seq 74.
3. **Task 3's test-scope dependency on pm/accounting.** **STILL OPEN — the one question the rulings did not reach.** Task 3 proceeds on `testImplementation` and flags it in the merge post rather than blocking, because the fallback (golden JSON) is strictly weaker: it cannot notice a module changing its own emission, which is the only thing the tripwire exists to catch. If the coordinator rules `testImplementation` a violation of rule 2, Task 3 changes shape and nothing else does.
4. **Who owns Reporting?** ANSWERED (seq 65 pt 4): najem-contacts, early start granted.
5. **Build time.** ANSWERED (seq 68 pt 3 / seq 65 pt 1): already fixed — @najem-usermgmt tagged the Keycloak suite `@Tag("keycloak")`, excluded from default `build`, always-on under `CI=true` or `-PkeycloakTests`. The 21m47s figure was the pre-tag world; measured default builds are now ~40s. **Reporting must not undo this**: its Testcontainers suites reuse the shared Postgres 16 image every module already pulls, and this plan adds no new container image. If a projection suite ever needs one, it gets the same tag treatment.

## Amendments after the rulings — what changed in this document

Recorded explicitly because a plan that silently disagrees with a ruling is a trap for the next reader.

- **Decision 1 rewritten** from "read the table wholesale" to the ruled allowlist, with the four-owner table and the reasoning for why default-private beats monitored-wholesale. The superseded recommendation is kept below it, marked.
- **Decision 2** marked ruled and confirmed.
- **Task 3 widened** from a single contract test to **one tripwire per consumed stream** (mandatory per the ruling), with the UM email-is-not-in-the-payload trap pinned.
- **Status banner** flipped from "PLAN ONLY, gate closed" to "IN BUILD".
- Open questions replaced with answers; question 3 is the only one still live.
