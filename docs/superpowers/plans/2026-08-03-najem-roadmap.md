# NAJEM Implementation Roadmap

**Stack (decided):** Java 21 + Spring Boot 3.3 (hexagonal, per `java-hexagonal-tdd`), modular monolith (Gradle multi-module; module = bounded context), hand-rolled event store on Postgres (jsonb payloads, transactional outbox), monorepo. FakeBank is a separate Spring Boot app in the same repo.

**Event delivery (decided):** transactional **outbox** for durability, but delivery = the outbox dispatcher **directly calling handler Java methods in the consuming module** (Spring beans implementing a shared handler interface from `contracts`) — **no queues, no brokers**. Modules still never compile-depend on each other; wiring happens at the composition root.

**Frontend (decided):** **HTMX** + server-rendered fragments (Thymeleaf) served by the monolith — no SPA.

**Multi-tenancy (decided, 2026-08-03):** **Workspace = agency = the hard tenancy boundary.** One workspace holds multiple properties and owners. Every module scopes data by `workspace_id` from day one; integration events carry `workspaceId` as their first field; cross-workspace queries don't exist in module code. User provisioning is **invite-only** (no self-signup). Access enforcement (Keycloak claims → workspace membership) lives in UserManagement + composition root.

**Identity (decided, 2026-08-03):** **Keycloak is IdP-only** — it owns users, credentials, login, and tokens, nothing else. Roles and workspace membership are NAJEM domain data in the event store (no Keycloak realm roles/groups); tokens prove identity, authorization resolves from UserManagement's own projection.

**Domain sources of truth:** `docs/event-storming/property-management-domain-model.md` (v1.1), `docs/event-storming/accounting-domain-model.md` (v1.0), `docs/event-storming/research/accounting-synthesis.md`.

## Phases

| Phase | Content | Parallelism | Plan |
|---|---|---|---|
| **0** | Monorepo scaffold, CI, docker-compose, `contracts` module (integration events, IDs), `platform:eventstore` (append/load + optimistic locking + outbox), **walking skeleton**: property → unit → tenancy → activate → charge → FakeBank statement → ingest → match → allocate → board green | Single agent (foundations must not fork) | `2026-08-03-phase0-setup-walking-skeleton.md` (ready) |
| **1** | Flesh out modules on the validated bones: **PM** (full event set §9, checklists, repairs, processes), **Accounting + Tenancy Accounting ACL** (components, credit notes, deposit lifecycle, matching ladder, trust equation), **FakeBank** (deterministic scenario/fixture engine — named reproducible scenarios, no randomness; MT940 export DEFERRED to Phase 2, landing together with its parser), **Contacts** (PII lookaside, interests, retention), **UserManagement** (Keycloak, workspaces) | **5 parallel agents**, one per module; contracts frozen — changes to `contracts/` require cross-agent sign-off | One plan per module, written at phase start |
| **2** | **Reporting** (Timeline projections — arrears COLOUR stays accounting-owned at /api/acc/board; Reporting composes, never recomputes it), **integrations** (real MT940 upload adapter; aggregator adapter behind same port), **frontend** (may start mid-Phase-1 against contracts: Unit Board, reconciliation screen, boards) | 2–3 parallel agents | `2026-08-04-phase2-reporting.md` (ready; EARLY START granted — read-side only) |
| **3** | e2e hardening (grow the skeleton's suite — one scenario per landed feature), performance passes, gap closure from hotspot logs | 1–2 agents | Checklist-driven |

## Sanctioned exception: Reporting reads streams directly (2026-08-04)

Reporting is a **privileged conformist read-side**: it reads the shared `events` table directly (as `event_type` + JsonNode payload — never another module's record classes, no Gradle dependency on any module), per the domain model's context map ("all events → Reporting projections"). Guardrails:
- **Stream allowlist, default-private:** Reporting may read only streams a module has declared readable. Initial allowlist — PM: `Property`, `Unit`, `Tenancy` · Accounting: `TenancyLedger`, `Payment` · Contacts: all (its events carry no PII by design) · UserManagement: `Workspace`, `User` ONLY (invitation/membership streams are private — adjacent to PII lookaside).
- Every consumed stream gets a **tripwire test** in Reporting that drives the owning module's real service and fails by name when a depended-on field disappears.
- A module changing a readable stream's payload should expect a Reporting tripwire failure, not a compile error — coordinate on the topic, don't treat it as breakage.

## Rules that keep parallel agents honest

1. **Contract freeze:** `contracts/` is the only shared surface between modules (besides `platform/`). Any change to it = stop-the-line, all affected agents informed.
2. **Module isolation:** modules depend on `contracts` + `platform` only, NEVER on each other (enforced via Gradle dependency constraints). Cross-module talk = integration events through the outbox.
3. **TDD everywhere** (`superpowers:test-driven-development`); behavioral tests (`behavioural-testing`) — test through ports, not internals.
4. **e2e is continuous:** each phase appends scenarios to `e2e/`; never a big-bang phase.
5. **No PII in events** (PII lookaside, decided); **no LLM mentions in commits; sign as the repo user.**
6. Deferred by domain decision (do NOT build): owner statements/tax packs/payouts, management-fee tracking, arrears-chasing workflow, prorating, soft close, aggregator choice (needs FakeBank first + sandbox trial).

## Walking-skeleton scope guard (Phase 0)

Deliberately minimal: single rent component, exact-reference matching only, manual confirmation, statuses `awaiting`/`green` only, no Keycloak, no S3, no deposit, no media. Everything else lands in Phase 1 on top of these bones.
