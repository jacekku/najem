# Event Storming Session — Accounting

**Date:** 2026-08-03
**Participants:** Jacek (domain expert), Claude (DDD facilitator)
**Scope:** Accounting core + Tenancy Accounting translation layer. Builds on `property-management-domain-model.md` (v1.1).

## Inherited constraints (from synthesis-review.md §F — the standing agenda)

1. **F1 Deposit lifecycle** (PM hotspot #4): cap per `legalForm` (12×/6×/3×), return ≤ 1 month from `vacateDate`, valorization (refund = multiplier × rent-at-return, never below nominal → deposit is a formula), deductions only against move-in/move-out `HandoverProtocolRecorded` evidence.
2. **F2 Charge-centric ledger, not invoice-centric**: atom = charge line on a per-tenancy ledger; invoices/documents are derived views. Recurring + one-off charges; line-item taxonomy `{rent, adminFee, mediaAdvance, deposit, repairRecharge, interest}`; year-in-advance = projection, not 12 manual acts; posting date ≠ due date.
3. **F3 One transfer, many components**: reconciliation by `paymentReference` must allocate one payment across charge components + handle partials, or the green/yellow/red board misreports. Media true-up: `MediaSettlementPrepared` → charge or credit, from meter readings.
4. **F4 Owner money vs pass-through**: only czynsz najmu is owner income; admin fee + media are pass-through. Owner statement: beginning balance → categorized income → categorized expenses (incl. **management fee — operator's own revenue**) → disbursement → ending balance. Payout per `PropertyOwnershipChanged` % shares.
5. **F5 Ryczałt tax cycle**: per-owner cash-basis received-rent report, monthly by the 20th; 8.5%/12.5% with 100k zł limit; tenant-borne media excluded from base.
6. **F6 Arrears hook**: emit `ArrearsReached3Periods` (computable, not just colors) as input to the future Arrears Termination Process.
7. **F7 `errorAnnulled` reversal**: Accounting reverses phantom-tenancy charges and keeps them out of the ryczałt base.

**PM-side handshake already fixed (inbound events):** `TenancyActivated` (deposit + rent charges), `TenancyEnded`/`vacateDate` (settlement, `DepositSettlementDue`), `RentChangeScheduled` (day-before charge at new amount, per component, warn-gate on unlawful unilateral increases), `HandoverProtocolRecorded` (meter readings), monthly total quoted to tenant with optional `{rent, adminFee, mediaAdvance}` breakdown.

---

## Phase 1: Domain Overview

**Q1 — Scope / official books?**
A: Keep **ledgers for: tenancies, repairs, deposits, the owner split, and the management split**. The owners have their own accountant — this system is NOT the official books, but build **as much double-entry rigor as possible** — reliability is the goal.

**Q2 — Actors?**
A: For now only the **property manager wearing an accounting hat**: overlooks arrears, creates charges when necessary, reconciles.

**Q3 — Bank statements?**
A: Bank integration is a whole mess: either direct open banking (big certificates, code inspection) or an **aggregator provider**. Either way the requirement is: **daily statement ingestion via API, automatic reconciliation preferred, show what's paid and what isn't**.

**Q4 — Whose accounts?**
A: Money lands in the **owner's account** (we connect to it). **Deposits likely have a separate account.** After the fundacja transfer, possibly a new account → model **multiple bank sources with a lifecycle** (from when data flows, when they expire/switch).

**Q5 — Formal documents?**
A: Tenants can **request an invoice/receipt** — generated from our data. Management-fee faktura possibly handled outside the system. Track: rent invoices/receipts, **supplier & repairman bills**, deposits, who-paid-what, the per-tenant split (income / admin fee / media). **Reconciliation is against invoices**: normally a monthly rent invoice per tenant; if a tenant pays several months at once, a "create up to X" button generates the missing invoices to reconcile against.

**Research fleet dispatched before Phase 2** (user request): accounting law/tax, comparable accounting systems & ledger design, bank integration (PL open banking / aggregators), deposit handling deep-dive. Reports land in `research/`. Session resumes on their completion with a reconciliation of findings.

**All four reports delivered + reconciled into `research/accounting-synthesis.md`** — 14 baseline corrections (incl. instytucjonalny cap 6× not 3×; deposits do NOT follow the building to the fundacja per SN III CZP 58/02), the three-layer component taxonomy position, a full pre-storming brief (CoA + 6 aggregates + 6 process managers + ~55 events + 13 policies + 8 read models), decision list D1–D15, and an 18-question professional list in 3 urgency tiers. Session continues with the D1–D15 walkthrough.

## Walkthrough answers

**Position 1 (three-layer component taxonomy + collapse rule): ACCEPTED.**

**Position 2 (one CoA + dimensions): ACCEPTED with user's own account structure:**
- Five top-level groups: **tenancy funds, landlord funds, supplier funds, deposit funds, suspense**.
- Every account links to a **separate jsonb table** holding its dimensions (tenancyId, userId, …) — dimensions live outside the account key.
- **Reconciliation screen requirement:** show **bank balance vs application balance**; after reconciliation they MUST be equal — otherwise **sound the alarm**. (= the trust equation surfaced as the daily screen/alert.)

Mapping to the synthesis CoA — CONFIRMED:
- tenancy funds ≈ receivables + tenant credits
- landlord funds ≈ ownerPayable (+ owner expenses)
- supplier funds ≈ pass-through liabilities (wspólnota, media) + repair bills
- deposit funds ≈ deposit liabilities (with debtorOwnerId)
- **suspense = unmatched/unallocated incoming cash** (confirmed)
- **NEW sixth group: "agency fees" / agency funds** — the operator's management-fee revenue, separate account group (keeps "whose money is this" clean)
- Bank accounts: separate bank-account CoA on the asset side, one per BankSource (confirmed)

## Phase 2: Event Discovery — cluster walkthrough answers

1. **Charging:** parking spot / storage — at most a separate line item, most likely folded into czynsz. No new recurring charge types.
2. **Ingestion:** confirmed — ingest ALL statement lines (debits too) and classify, not just credits.
3. **Reconciliation:** 4-tier ladder + payer-account memory matches expectations; reconciliation will grow post-MVP.
4. **Deposits:** bank transfer only, never cash — no cash-deposit path needed.
5. **SCOPE CUT — owner reporting/tax cluster:** The owner will NOT use expense tracking, owner statements, tax/ryczałt packs. "We just need to know that the tenant paid." It's all the owner's account; **no payout exists** (manager is connected to the owner's account; money already sits there). → `OwnerStatementPrepared`, `RyczaltPackPrepared`, `OwnerPayoutInitiated/Confirmed`, `OwnerExpenseRecorded` dropped from MVP. (Clarification pending: fate of management-fee tracking / agency-fees group, gmina 186a report, VAT tracker, soft close.)
**Collision-check resolutions:**
1. Owner report = **payment-status view, per unit and per property** (who paid / late / arrears). Not a financial statement. CONFIRMED.
2. **Management fee tracking DROPPED from MVP** ("too specific") — no `ManagementFeeCharged`; agency-fees CoA group stays reserved but dormant. D2 moot for MVP.
3. Gmina 186a report: **KEEP**. VAT 240k tracker: **KEEP** (silent background warning). Soft period close: **DROP**. CONFIRMED.
4. **All six CoA groups + bank assets stay** — the cut removes owner-facing documents/processes, not ledger structure; trust-equation alarm needs every złoty classified. CONFIRMED.

**Scope philosophy (user question, facilitator answer):** Narrowing to PM + minimal accounting is the RIGHT move — the core domain is tenancy lifecycle + payment visibility; owner statements/tax packs are supporting subdomains. Because the system is event-sourced, cutting them costs nothing permanently: the *facts* (charges, payments, allocations, deposits) keep being recorded, and any dropped report/projection (owner statement, ryczałt pack) can be built later **retroactively over full history**. Research is banked in `research/`; seams reserved (events named, CoA groups dormant).

6. **Corrections = credit-note model:** unpaid charge/invoice/bill can simply be deactivated (active flag → reversal); if it's already PAID and must change → **credit note** (`CreditNoteIssued` linked to the charge/invoice). Ledger mechanics stay reversal-only; the credit note is the document face.
7. **Rent deductions** (e.g. hot-water-outage rent-free week from PM session): credit note if already reconciled, or modify the invoice before reconciliation. No dedicated event. Final sweep: no move-in fees, no special wspólnota-refund handling (classified as non-tenant lines).

## Phase 3: Timeline & Pivotal Events

Structure: **daily loop** (fetch → ingest all lines → matching ladder → queue → allocate → board update → trust check/alarm) · **monthly loop per tenancy** (charge → invoice → payment → color transition) · **tenancy arcs** (activation charges; rent-change −1d; ending → true-up → DepositSettlementDue clock; errorAnnulled reversals) · **slow arcs** (180d consents, fundacja BankSourceSuperseded + Deposit Migration, annual true-up, gmina half-yearly, VAT rolling).

**Pivotal events:** `TransactionIngested` (outside truth enters) · `PaymentAllocated` (the "did they pay?" answer) · `DepositSettlementDue` (the litigation clock).

**Validation answers:**
1. **Charge/invoice auto-creation: due date −1 day.** BUT needs live data on payment behavior — many tenants may pay by the 1st; for those, invoices are created manually earlier and **auto-creation is stopped per tenancy** (per-tenancy auto/manual toggle; "create up to X" covers advance payers). Revisit with production data.
2. **Color thresholds:** **Golden** = paid through the tenancy end date. **Green** = current period paid. **Yellow** = current charge unpaid but not yet overdue ("they normally pay by the due date — payment not in yet"; behavioral baseline is a future refinement, no data yet). **Red** = in arrears from day 1 past due. **Bright red** = ≥ 1 full period unpaid (feeds the 3-full-periods counter).
3. **Auto-allocation: BUILD the automation, ship it SWITCHED OFF** — every match requires manager confirmation for now; flip the flag when trust is earned.

## Phase 4+5 (consolidated): CONFIRMED
Aggregates: TenancyLedger, IncomingPayment, BankSource, Deposit, RepairBill (OwnerAccount dropped — owner = PM reference data + dimension). Contexts: Accounting core · Tenancy Accounting (ACL) · **Bank Integration as its own supporting context** (port + MT940 adapter now, aggregator adapter later) · compliance projections in Reporting. Policies + read models per synthesis §3.4/3.5 minus the owner-reporting cuts, plus colors/auto-off/invoice-timing amendments.

## Phase 6: Hotspots (internal)
1. **Suspense aging:** warn 7d / red 30d accepted as suggestion — **needs lawyer/accountant confirmation**. Practice expectation: unmatched money → notify same day, decide within a week. (→ professional question P19: obligations around unidentified/unattributable funds.)
2. **Aggregator NOT locked.** Instead: **build a FAKE BANK as a separate app** implementing the BankSource port (statement generation, transactions) to test all flows end-to-end before any real integration.
3. **Yellow refinement:** yellow applies when unpaid-not-overdue AND (first month OR baseline data confirms they normally pay on time).
4. Per-tenancy invoice timing: parked pending production payment-behavior data.
5. **Known future pain: arrears chasing** — flagged; MVP shows the board, chasing workflow (warnings, cure periods, art. 11 sequence) is deliberately post-MVP with events reserved (`ArrearsWarningSent`, `ArrearsReached3Periods`).

**D1–D15: ALL DEFAULTS ACCEPTED** — oldest-due-first with rent-last allocation (data-driven overrides), mgmt fee % of collected rent on allocation, interest engine OFF by default with InterestWaived events, per-owner deposit accounts, media true-up annual + tenancy end, MT940-first → aggregator → never own AISP, fundacja fields now + migration fast-follow (transfer only with counsel), KSeF seam only, gmina 186a report built, PII financial-hold, deposit as receivable at activation, pass-throughs informational-only, near-mandatory contract-split warn, VAT 240k rolling tracker, soft close warn + v2.
