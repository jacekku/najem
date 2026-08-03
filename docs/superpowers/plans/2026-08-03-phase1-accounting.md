# Phase 1 — Accounting + Tenancy Accounting ACL

**Agent:** najem-accounting · **Branch/worktree:** `najem-accounting/phase1-accounting` @ `../najem-wt/najem-accounting`
**Sources of truth:** `docs/event-storming/accounting-domain-model.md` (v1.0) · `docs/event-storming/research/accounting-synthesis.md` (+ law/ledger/bank/deposit reviews) · `docs/superpowers/plans/2026-08-03-najem-roadmap.md`
**Coordination rulings applied:** Phase 0 gate + 7 conventions (najem-build seq 15) · Flyway range **V30–V39**, prefix `db/acc` (seq 19) · **workspace = hard tenancy boundary**, acc_* tables carry `workspace_id`, board + trust equation per workspace (seq 21) · leading-`workspaceId` on every integration event (seq 22) · worktrees + self-merge a–d (seq 30).

---

## 1. What Phase 0 left us

Working walking-skeleton bones in `modules/accounting`:

- `LedgerService.postRentCharge` — one hardcoded `rent` charge, `ChargePosted` to the event store + `acc_charge` row, tenancy status `awaiting`.
- `IngestionService` — `BankStatementPort.fetchSince`, dedupe by `external_id`, **tier 1 only** (exact `payment_reference` + exact amount), `PaymentIngested`, `acc_payment`, `acc_suggestion`.
- `ReconciliationService.confirm` — `PaymentAllocated`, charge flagged `allocated`, tenancy status `green`.
- ACL: `TenancyActivatedHandler` → `postRentCharge`.
- Schema V3: `acc_charge`, `acc_payment`, `acc_suggestion`, `acc_tenancy_status`. No `workspace_id` anywhere.
- REST `/api/acc/**`; `AccEventTypes` registry.

Everything below grows on those bones. **The `WalkingSkeletonTest` stays green at every commit** — it is the regression floor, not scaffolding to be replaced.

## 2. Scope

**In (Phase 1):** workspace scoping · component taxonomy + collapse rule · charge lifecycle (deactivation, credit notes) · matching ladder tiers 2–4 **built but OFF** · allocation ordering + partial/over payment · payment reversal & amendment · suspense + aging · deposit lifecycle incl. valorization and settlement clock · trust equation + per-owner deposit sub-check · arrears board full colours · interest as explicit events · ACL warn-gates (deposit cap, unlawful increase, missing split) · retention holds (joint CCR).

**Out (explicitly, per roadmap + rulings):** owner statements / tax packs / payouts / management-fee tracking / arrears-chasing workflow / prorating / soft close (domain-deferred) · MT940 (Phase 2, human ruling seq 29) · real aggregator adapter (Phase 2) · reporting projections beyond my own read models (Phase 2) · frontend.

**Non-negotiables I hold myself to:** TDD, behavioural tests through ports not internals · no PII in events (ContactId only) · corrections are reversal-only, never mutation · Σ Dr = Σ Cr at construction · no cross-module compile dependency; PM reaches me only through `contracts` events.

## 3. Design decisions taken up front

| # | Decision | Why |
|---|---|---|
| D1 | `workspace_id uuid not null` on every acc_* table; every query filters it; charges inherit workspace from `TenancyActivatedEvent.workspaceId` | seq 21 pt 4. Cheapest now, painful later. |
| D2 | Component is a **dimension on the charge line**, not an account | ledger-review §1.1; synthesis §2(a). |
| D3 | **Collapse rule**: `componentSplitInContract == false` ⇒ the whole amount posts as `rent`, with a warning surfaced from the ACL | synthesis §2(a). Full taxability + full valorizability is the safe default. |
| D4 | Deposit **multiplier = deposit ÷ rent-at-signing**, stored immutable decimal at charge time; cap checked against signing-day rent (12× zwykły / 6× okazjonalny / **6×** instytucjonalny — C1 correction, *not* 3×); valorized refund = multiplier × rent-at-return, **floored at nominal**, base = `rent` component only | synthesis C1/C4/C5, DEPOSIT §0–1. |
| D5 | Settlement clock = `max(vacateDate, moveOutProtocolDate) + 1 month` | synthesis C3 — delaying the protocol shifts the deadline at the landlord's risk. |
| D6 | Ladder tiers 2–4 are **implemented and unit-tested but disabled by config** (`acc.matching.tiers-enabled`, `acc.matching.auto-confirm=false`) | domain model §4 "auto-allocation built but OFF"; the manager confirms every match at launch. |
| D7 | Allocation order: oldest due first; within a date `interest → mediaAdvance → adminFee → repairRecharge → rent` last. Stored as **data** (ordered component list in config), not a hardcoded comparator | domain model §4 "overridable as data". |
| D8 | Interest is a **right, not a duty**: `InterestAccrued` / `InterestWaived` explicit events, per-tenancy toggle default OFF, NBP rate stored as **rate windows** | synthesis C13, KC art. 481. |
| D9 | Arrears colour is a **projection recomputed from charges+allocations**, never a stored mutable status. `acc_tenancy_status` becomes derived. Bright-red counts **full unpaid periods, not amounts** | domain model §6, synthesis C8 (art. 11). |
| D10 | Trust equation is a **check that raises an alarm event**, not a constraint that blocks. Per workspace; per-owner deposit sub-check separate | domain model §2. Expert system: warn, don't wall. |
| D11 | `errorAnnulled` reversal path nets projections to zero and is **excluded from every income base** | domain model §4, synthesis C9. |

**Open, proceeding on stated defaults** (per seq 26 recommended-default rule; HUMAN-QUESTIONs in §6): suspense aging thresholds (default 7d warn / 30d red) · yellow behavioural baseline (default: first period of a tenancy only) · per-tenancy invoice timing (default due−1d).

## 4. Task breakdown (TDD, in order)

Each task = red → green → refactor, behavioural tests through the service ports. Every task ends with `./gradlew build` green (incl. e2e) before it is considered done. Tasks 1–3 are prerequisites for everything else; 4–11 are largely independent.

| # | Task | Deliverable | Tests (behaviour, not internals) |
|---|---|---|---|
| **1** | **Workspace retrofit** — `V30__acc_workspace.sql` adds `workspace_id` to acc_charge/acc_payment/acc_suggestion/acc_tenancy_status (backfill dev-workspace constant), all services take/filter workspace; ACL reads `event.workspaceId()` | schema + service signatures | two workspaces with identical payment references do not cross-match; board of workspace A never shows workspace B |
| **2** | **Component taxonomy + collapse rule** — component enum `rent\|adminFee\|mediaAdvance\|deposit\|repairRecharge\|interest`; charge carries it; ACL applies collapse when the contract has no split and emits a warning | `Component`, `ChargePosted` gains component (already has the field — becomes typed), ACL warn | split contract posts 3 lines; unsplit contract posts 1 `rent` line + warning surfaced; valorization base ignores non-`rent` |
| **3** | **Charge lifecycle + corrections** — `ChargeDeactivated` (unpaid only, reversal underneath), `CreditNoteIssued` (paid charges), amendment refused on the wrong path | ledger service ops | deactivating a *paid* charge is refused and a credit note is required instead; credit note leaves an audit pair, never mutates the original |
| **4** | **Matching ladder tiers 2–4 (built, OFF)** — t2 reference-only, t3 heuristic + **remembered payer account** (reinforced on confirm), t4 manual queue; strategy chain, config-gated | `MatchingLadder` + `acc_payer_account` memory (V31) | garbled reference + known payer IBAN ⇒ t3 suggestion; with tiers disabled the same line lands in the manual queue; confirming reinforces the mapping |
| **5** | **Allocation engine** — order per D7, partial payment (allocated ≤ charged), overpayment → surplus to suspense, one transfer across many charges | `AllocationService` | 2500 against a 3000 charge leaves 500 open and the board red; 3500 allocates 3000 and books 500 suspense; interest is consumed before rent |
| **6** | **Suspense + non-tenant lines** — `TransactionClassified` / `PaymentMarkedNonTenant`; debits ingested too; suspense aging warn 7d / red 30d | classification + aging projection | a debit line ingests without becoming a payment; unmatched credit ages into warn then red |
| **7** | **Reversals** — `PaymentReversed` (NSF) reopens charges **at their original due dates**; `PaymentAllocationAmended` for wrong-tenant-found-later | reversal ops | after reversal the tenancy returns to the exact colour it had pre-payment; amendment moves money between tenancies leaving both ledgers balanced |
| **8** | **Deposit aggregate** — charge (multiplier snapshot + cap warn per legalForm), partial receipts → Held, instytucjonalny draw-down ⇄ replenishment | `Deposit` aggregate (V32) | 13× rent on zwykły warns but does not block; multiplier survives a later rent change unchanged |
| **9** | **Deposit settlement** — `DepositSettlementDue` (D5 clock), `DepositValorizationComputed` (floor at nominal), `DeductionClaimed` (evidence refs, warn without move-in protocol), returned/withheld/forfeited/overdue/disputed | settlement process manager | rent fell since signing ⇒ refund floors at nominal; deadline computed from the protocol date, not vacate, when the protocol is later; forfeiture flags taxable income that month |
| **10** | **Trust equation + Deposit Register** — bank vs application balance per workspace, alarm event on drift; per-owner deposit three-way sub-check; valorization headroom prompt | daily check + `DepositRegister` read model | injecting a phantom bank line makes the equation fail loudly and the reconciliation screen say so |
| **11** | **Arrears board (full colours)** — golden/green/yellow/red/bright-red as a recomputed projection; `ArrearsReached3Periods` counter on full periods | board projection replacing stored status | paid-through-end ⇒ golden; one full period unpaid ⇒ bright red; two partial payments spanning a period do **not** trip the counter |
| **12** | **e2e scenario** — append one accounting scenario file to `e2e/` (new file only, no edits to skeleton test logic — seq 30 rule d): tenancy → deposit charged → rent charges → partial payment → credit note → tenancy end → media true-up → valorized deposit return | `e2e/AccountingLifecycleTest` | one narrative test proving the arcs compose |

**Interest (D8)** rides along with task 5 (`interest` must be allocatable) and is completed in task 11's colour logic; it does not get its own task because it is two events plus a rate-window table (V33).

## 5. Contract-change requests (to coordinator, not raised yet)

1. **Retention holds — joint CCR with @najem-contacts** (pre-approved in principle, seq 19 pt 6). Accounting emits hold set/released when a contact becomes ledger-referenced (~5–6y tax + civil prescription); Contacts keeps the projection and refuses erasure. Exact record shapes submitted jointly once task 3 has fixed the ledger's contact-reference points.
2. **Possible: `ChargePosted` / `PaymentAllocated` as integration events** — only if Reporting (Phase 2) needs them before its own projections exist. Not requested now; my read models cover Phase 1. Would follow the leading-`workspaceId` convention.
3. **Nothing else.** Accounting consumes PM's events; it does not need PM to consume mine in Phase 1.

## 6. HUMAN-QUESTIONs (via coordinator, non-blocking — proceeding on defaults)

| Q | Why it shapes the work | My default if unanswered |
|---|---|---|
| Suspense aging thresholds — is 7d warn / 30d red right, and what obligation attaches to unidentified funds? (hotspot A1 / P19) | Drives the reconciliation queue's urgency signals | 7d / 30d, configurable |
| Yellow's "normally pays on time" baseline (A3) | Distinguishes yellow from red on day 1 past due | Yellow = first period of a tenancy only; no behavioural inference until live data |
| Is `adminFee` a lawful tenant-facing component, or does it fold into `rent`? (P4 / synthesis C10) | Decides whether the taxonomy keeps 6 components or 5 | Keep `adminFee`, gate it behind the contractual-assumption clause + warn-gate (the synthesis position); folding it later is a data migration, not a redesign |
| Interest: charge it by default or never without instruction? (C13) | It is a right, not a duty | Per-tenancy toggle, default OFF |

## 7. Sequencing and merges

Tasks 1–3 land as separate small self-merges (seq 30 a–d: my module dir + my tests + this plan only). Tasks 4–11 likewise, one merge per task, rebasing on main each time. Task 12 touches `e2e/` — new file only, still within rule d, but I will post READY-FOR-REVIEW rather than self-merge since it is the shared suite. Any task that turns out to need `contracts/`, `platform/`, `PlatformConfig`, `application.yml` or another module stops and becomes a CCR.

STATUS posts to `najem-build` at start and at each merge, with commit hashes.
