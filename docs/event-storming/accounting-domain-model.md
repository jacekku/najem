# NAJEM — Accounting Domain Model

**v1.0** — compiled from the Accounting event-storming session of 2026-08-03.
Raw record: `session-2026-08-03-accounting.md` · Working research: `research/accounting-synthesis.md` (+ 4 underlying reports) · Companion: `property-management-domain-model.md` (v1.1).

---

## 1. Domain Overview

### Summary
The Accounting context answers one question reliably: **"did the tenant pay?"** It keeps a double-entry-rigorous ledger of charges and payments per tenancy, ingests bank statements daily (all lines, debits included), matches payments by reference through a 4-tier ladder, and drives the arrears board (golden/green/yellow/red/bright-red) per unit and per property. It is **not the official books** — owners have their own accountants. MVP deliberately excludes owner statements, tax packs, payouts, and management-fee tracking; because everything is event-sourced, those are projections that can be added later **retroactively over full history**.

**Philosophy carried over:** expert system — warnings and confirm-gates, not walls; corrections are reversal-only (credit notes as the document face); the ledger, not the bank, is the source of truth for whose money it is.

### Actors
- **Property Manager (accounting hat)** — sole day-to-day actor: watches the board, works the reconciliation queue, confirms matches, creates charges/credit notes, handles deposits
- **Tenant** — pays by transfer with the agreed `paymentReference`; may request an invoice/receipt
- **Owner** — not a user; money sits in their account; their accountant is served indirectly (data exists for later projections)

### External Systems
- **Bank feeds** via the `BankSource` port: MT940 manual upload (MVP) → aggregator adapter (Enable Banking / Kontomatik — decided after sandbox trial) → own AISP never at this scale
- **Fake Bank (separate app, to build)** — implements the BankSource port (accounts, statements, transactions) for end-to-end flow testing before any real integration
- **S3** — invoices, credit notes, settlement documents, evidence packs

---

## 2. Chart of Accounts

One CoA; dimensions live in a **separate jsonb table** linked per account (`tenancyId`, `ownerId`, `component`, `bankSourceId`, `purpose`, `debtorOwnerId`, …). The "ledgers" are filtered projections.

| Group | Side | Content |
|---|---|---|
| **Bank accounts** | Asset | One per BankSource; dims `{ownerId, purpose: operating\|deposit}` |
| **Tenancy funds** | Asset (receivable) / Cr (credit) | Charges receivable + tenant credit balances |
| **Landlord funds** | Liability | Rent income accumulating for the owner (no statements/payouts in MVP — classification only) |
| **Supplier funds** | Liability | Pass-throughs: wspólnota admin, media advances (drained by true-up); repair bills |
| **Deposit funds** | Liability | Nominal deposit liabilities; dim `debtorOwnerId` (migration); valorization is a projection |
| **Suspense** | Liability | Unmatched/unallocated incoming cash |
| **Agency fees** | Revenue | RESERVED, dormant (management-fee tracking cut from MVP) |

**Component taxonomy (three layers, accepted):** tenant sees ONE total → contract carries the legal split (czynsz / expressly-assumed admin pass-through / media advances — lawyer P4 validates wording) → ledger keeps components as a dimension: `rent | adminFee | mediaAdvance | deposit | repairRecharge | interest`. **Collapse rule:** no contractual split ⇒ everything posts as `rent` (fully taxable + fully valorizable) with a warning at reservation.

**Mechanics:** versioned pure **posting rules** map events → balanced transactions (Σ Dr = Σ Cr at construction); bitemporal (`effectiveDate`, `recordedAt`); corrections = compensating reversals only.

**Trust equation (the alarm):** the reconciliation screen shows **bank balance vs application balance — after reconciliation they MUST be equal, otherwise sound the alarm.** Daily checks: three-way global equation, per-owner deposit sub-check (deposit bank acct = Σ HELD liabilities = Deposit Register), valorization headroom (Σ valorized refunds vs deposit cash → top-up prompt).

---

## 3. Aggregates

| Aggregate | Purpose / key invariant |
|---|---|
| **TenancyLedger** (per tenancy) | Charge lines with component dimension, active flags, credit notes; allocated ≤ charged per line; per-tenancy auto/manual invoice-creation toggle |
| **IncomingPayment** (per bank credit line) | Lifecycle of one payment: ingested → suggested → confirmed → allocated → (reversed); owns its match; never allocated beyond its amount |
| **BankSource** | Feed + consent lifecycle: `Pending → Active → RenewalDue(−14d) → Expired/Revoked/Superseded → Archived`; kinds `manualFile \| aggregatorConsent \| directApi`; idempotent import by line hash |
| **Deposit** (per tenancy) | Multiplier snapshot at signing (deposit ÷ rent, immutable decimal); cap warn per legalForm (**12× zwykły / 6× okazjonalny / 6× instytucjonalny**); states: Charged → Received(partials) → Held → [DrawnDown ⇄ Replenished (instytucjonalny)] → SettlementDue → Settled(Returned/PartiallyWithheld/Forfeited) / Disputed / Transferred / RolledOver |
| **RepairBill** | Supplier/repairman bills; owner-borne vs recharged-to-tenancy (nota obciążeniowa basis) |

*Owner is NOT an aggregate here — reference data from PM + a dimension.*

---

## 4. Event Flow

### Daily loop (heartbeat)
`StatementFetched` (06:00 API fetch ≤4/day, or manual MT940 upload; `StatementOverdueDetected` nags idle manual sources) → `TransactionIngested` (every line) → non-tenant lines `TransactionClassified` / `PaymentMarkedNonTenant` → matching ladder: exact reference+amount → reference-only → heuristic + remembered payer account → manual queue → `PaymentMatchSuggested` → **manager confirms (auto-allocation built but OFF)** → `PaymentMatchConfirmed` → `PaymentAllocated {allocations[{tenancyId, chargeId, component, amount}]}` (order: oldest due first; within a date interest → media → admin → repairRecharge → rent last; overridable as data) → board updates → trust check → alarm on drift.
Unmatched money: notify same day, decision expected within a week; suspense aging warn 7d / red 30d *(thresholds pending accountant confirmation — P19)*.

### Monthly loop (per tenancy)
`ChargePosted` at due−1d (unless per-tenancy manual mode; "create up to X" pre-generates future charges for advance payers) → `InvoiceIssued` (derived doc; VAT-zw art. 43 ust. 1 pkt 36) → payment resolves via daily loop → color transitions.

### Tenancy arcs (from PM via Tenancy Accounting ACL)
- `TenancyActivated` → deposit charge (multiplier snapshot, cap warn) + first rent charge
- `RentChangeScheduled` effective−1d, if unchanged → charge at new amount (warn-gate on unlawful `unilateralIncrease`)
- `TenancyEnded` → charging stops; media true-up (`MediaSettlementPrepared` from move-out protocol readings → charge or credit); `DepositSettlementDue {deadline = max(vacateDate, moveOutProtocolDate) + 1 month}` → `DepositValorizationComputed {rentAtReturn, floorApplied}` → `DeductionClaimed` (evidence refs; warns without move-in protocol; 1-year prescription note) → `DepositSettlementPrepared` → `DepositReturned / DepositPartiallyWithheld / DepositForfeited` (forfeiture ⇒ taxable income that month) / `DepositReturnOverdue` / `DepositDisputed`
- `TenancyEnded(errorAnnulled)` → reverse all charges; received money → suspense (refund or re-match); projections net to zero

### Corrections
Unpaid charge → deactivate (`ChargeDeactivated`, reversal underneath). Paid charge needing change → **`CreditNoteIssued`** (rent deductions, e.g. outage compensation, land here or as pre-reconciliation invoice edits). `PaymentReversed` (NSF) reopens charges at original due dates. `PaymentAllocationAmended` for wrong-tenant-found-later.

### Slow arcs
180-day consent renewals (`ConsentRenewalRequested` at −14d) · fundacja: `BankSourceSuperseded` + **Deposit Migration Process** (per-tenancy przejęcie długu with tenant consent — SN III CZP 58/02; unconsented deposits stay the old owner's legacy liability via `debtorOwnerId`) · annual media true-up + always at tenancy end · `RentMirrorReportExtracted` (gmina art. 186a, half-yearly) · `VatLimitWarningRaised` (rolling 12m vs 240k) · `RetentionHoldSet/Released` (ledger-referenced contacts erasable only after ~5–6y tax + civil prescription).

**Pivotal events:** `TransactionIngested` · `PaymentAllocated` · `DepositSettlementDue`.

---

## 5. Process Managers

1. **Monthly Charging** — rent-day schedule per tenancy; posts at due−1d; respects per-tenancy manual mode and pre-generated charges.
2. **Reconciliation** — ladder → queue → confirmation → allocation; automation flag OFF at launch.
3. **Deposit Settlement** — settlement-due clock → valorization → prefilled deductions (arrears + protocol deltas + media true-up) → itemized statement → return/withhold; −7d warning; overdue = litigation-risk red.
4. **Deposit Migration** (on `PropertyOwnershipChanged`) — per HELD deposit: consent doc → cash move → `DepositObligationTransferred`; mixed-portfolio state supported.
5. **Consent Renewal** — 180-day cycle nudges with reconnect links.
6. **Media Settlement** — true-up from protocol/meter readings + supplier invoices; written zestawienie on every advance change.

---

## 6. Read Models

1. **Arrears board** (the product's face) — per unit AND per property: **golden** = paid through tenancy end date · **green** = current period paid · **yellow** = unpaid, not yet overdue (first month, or baseline shows they normally pay on time) · **red** = in arrears from day 1 past due · **bright red** = ≥1 full period unpaid (feeds the `ArrearsReached3Periods` counter — full periods, not amounts, per art. 11)
2. **Reconciliation screen** — bank balance vs application balance (equal or alarm), suggestion queue, suspense aging
3. **Deposit Register** — per owner: nominal, multiplier, current valorized refund, state, deadline; feeds statutory-deadlines lane
4. **Bank source health** — consent validity, last fetch, failure reasons
5. **Timeline feed** — accounting facts (invoice generated, paid, unpaid-red) into PM's multi-level Timeline
6. **Gmina 186a extract** · **VAT 240k rolling tracker** (silent warning) · **retention-hold register** (PII lookaside gate)

**Cut from MVP (recoverable retroactively from events):** owner statements, ryczałt packs, payouts, owner expenses, management-fee tracking, soft period close.

---

## 7. Context Map (accounting side)

| Upstream | Downstream | Pattern | Notes |
|---|---|---|---|
| Property Management | Tenancy Accounting | OHS → ACL | PM events → charge commands; compliance warn-gates live here (deposit cap, unlawful rent increase, missing contract split) |
| Tenancy Accounting | Accounting core | Customer–Supplier | Only writer of tenancy-driven charges |
| **Bank Integration** | Accounting core | ACL/port | `BankSource` port: MT940 adapter (MVP) → aggregator adapter → same pipeline; **Fake Bank app** tests the port |
| Accounting core | Reporting | Conformist | Board, timeline feed, compliance projections |

---

## 8. Key Scenario: A month in the life

1. 3rd of the month: tenant's charge for October was posted Sep 9 (due−1d before the 10th); Anna pays 3000 zł on the 3rd with reference "NAJEM/12/2026/A-KOW"
2. 06:00 daily fetch → `TransactionIngested`; ladder tier 1 hits (exact reference + amount) → suggestion appears in the queue
3. Manager confirms → `PaymentAllocated`: oldest open charge; within it media → admin → rent last; board flips Anna to **green**
4. Another tenant's transfer arrives from his mother's account, reference garbled → tier 3 remembers the payer account → suggested, manager confirms; the mapping is reinforced
5. A third payment matches nothing → suspense; manager notified same day, resolves within the week (wrong reference — allocated manually)
6. Reconciliation screen: bank balance == application balance → no alarm
7. Mid-month: hot water was out a week; manager issues `CreditNoteIssued` against November's rent charge
8. Month-end: one tenancy ends → move-out protocol (meter readings) → media true-up credit 180 zł → `DepositSettlementDue` (deadline in 1 month) → valorization computed (multiplier 1.5 × current rent) → no deductions (protocols match) → `DepositReturned` 12 days later
9. A tenant hits one full period unpaid → **bright red**; chasing remains manual (post-MVP workflow), but the counter marches toward `ArrearsReached3Periods`

---

## 9. Hotspot Log (accounting)

| # | Item | Status |
|---|---|---|
| A1 | Suspense aging thresholds (7d/30d) + unidentified-funds obligations | Suggested — confirm with lawyer/accountant (P19) |
| A2 | Aggregator choice (Enable Banking vs Kontomatik) | Deferred — decide after Fake Bank + sandbox trials |
| A3 | Yellow behavioral baseline ("normally pays on time") | Parked until live data |
| A4 | Per-tenancy invoice timing (many pay by the 1st) | Parked until production payment data |
| A5 | **Arrears chasing workflow** | Known future pain — post-MVP; events reserved (`ArrearsWarningSent`, `ArrearsReached3Periods`) |
| A6 | Who does media true-up today (PM hotspot #13) | Ask domain expert |
| A7 | P1–P18 professional questions (`research/accounting-synthesis.md` §5) | With lawyer/tax advisor; **P1–P3 time-critical before fundacja transfer** |

## 10. Ubiquitous Language (additions)

| Term | Definition |
|---|---|
| Charge | A dated, component-typed claim against a tenancy; the reconciliation atom |
| Component | `rent \| adminFee \| mediaAdvance \| deposit \| repairRecharge \| interest` — ledger dimension |
| Suspense | Received money not yet attributed/allocated |
| Allocation | Spreading a confirmed payment across charge lines per the allocation order |
| Trust equation | Bank balance == application balance after reconciliation, or alarm |
| Multiplier | Deposit ÷ rent at signing; immutable decimal; valorization key |
| Valorization | Refund = multiplier × rent at return, floored at nominal; base = `rent` component |
| Collapse rule | No contractual split ⇒ whole amount is `rent`: fully taxable, fully valorizable |
| Credit note | Document face of a reversal on an already-paid charge |
| Golden / green / yellow / red / bright red | Paid-through-end / period paid / pending-not-overdue / overdue day 1 / ≥1 full period unpaid |
| Fake Bank | Separate test app implementing the BankSource port |
