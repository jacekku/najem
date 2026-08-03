# Accounting Synthesis — Pre-Storming Working Document

**Date:** 2026-08-03 · **For:** the Accounting event-storming session (Phase 2)
**Inputs:** `../session-2026-08-03-accounting.md` (scope + §F + Phase 1), PM model v1.1 (§9), and the four research reports: `accounting-law-review.md` [LAW], `ledger-design-review.md` [LEDGER], `bank-integration-review.md` [BANK], `deposit-handling-review.md` [DEPOSIT].
**How to use:** the product owner walks §1 → §4 item-by-item, answers the decisions in §4, then storming begins from the §3 event clusters. §5 goes to the lawyer/tax advisor in parallel.

---

## 1. Corrections to the inherited baseline (§F / PM v1.1)

| # | Baseline said | Correction | Source |
|---|---|---|---|
| C1 | **F1**: deposit caps 12×/6×/**3×** | Instytucjonalny cap is **6×** (art. 19f **ust. 5** u.o.p.l., raised from 3× by the 2019 KZN amendment; note ust. 5, not ust. 4). Table becomes **zwykły 12× (art. 6 ust. 1) / okazjonalny 6× (art. 19a ust. 4) / instytucjonalny 6× (art. 19f ust. 5)**. Fix before the ACL cap warn-gate is built. | LAW §1, DEPOSIT §0.1 (both agree) |
| C2 | SCA re-consent every **90 days** | **180 days** since the EBA RTS amendment applied 25.07.2023. Also: background AIS access capped at **4 calls/day/account** (RTS 2018/389 art. 36(5)) — daily ingestion fits. | BANK §1 |
| C3 | **F1**: return ≤ 1 month from `vacateDate` | Clock start = **`max(vacateDate, HandoverProtocolRecorded(moveOut).date)` + 1 month** — courts treat opróżnienie as confirmed by the move-out protocol; delaying the protocol shifts the deadline at the landlord's risk. Sharpen `DepositSettlementDue` (v1.1 has `vacateDate + 1 month`). | DEPOSIT §1 |
| C4 | **F1**: "deposit is a formula" (multiplier × rent-at-return) | Sharpened: **multiplier = deposit ÷ rent-at-signing, stored as a decimal, immutable from charge time** ("krotność przyjęta przy pobieraniu"); cap is checked against **signing-day rent**, valorization against **return-day rent**; floor = nominal. | DEPOSIT §0.1, §1 |
| C5 | (implicit) valorization = zwykły-only concern | **Valorization applies to all three legalForms** — art. 19e (okazjonalny) and art. 19j (instytucjonalny) both incorporate art. 6 ust. 3. Popular "okazjonalny returns nominal" guidance contradicts statute text (lawyer Q, §5 P6). | DEPOSIT §0.3 |
| C6 | **F1** lifecycle had no mid-tenancy branch | **Instytucjonalny allows mid-tenancy draw-down** from the deposit for missed payments (art. 19f ust. 7) + contractual replenishment — an extra lifecycle branch arriving with the fundacja era. No statutory equivalent for zwykły/okazjonalny. | DEPOSIT §0.2 |
| C7 | `PropertyOwnershipChanged` reroutes payouts (F4) — deposits implicitly follow | **The deposit-return debt does NOT pass to the acquirer by law** (KC art. 678 moves the lease only; uchwała SN III CZP 58/02 — the deposit is a separate obligation). Fundacja becomes debtor only via **przejęcie długu (KC 519/522): written form + tenant's consent, per tenancy**. Requires a **Deposit Migration Process** and a representable **mixed portfolio state** (unconsented deposits stay the old owner's legacy liability). | DEPOSIT §5 |
| C8 | **F6**: `ArrearsReached3Periods` "computable, not just colors" | Compute on **full charge periods unpaid, not amounts** (art. 11 ust. 2 pkt 2 u.o.p.l.); the future termination process must also model the **1-month written warning with an extra month to pay**. | LAW §4.11 |
| C9 | **F5**: base = received rent, media excluded | Base **widened**: retained/forfeited/drawn-down deposit = **przychód z najmu in the month of retention** (cash basis); holdover compensation likewise flag-worthy; `errorAnnulled` reversals excluded (F7 confirmed). PIT-28 window is 15.02–30.04 (not end-Feb). | DEPOSIT §3/§4, LAW §3A |
| C10 | **F2** taxonomy `{rent, adminFee, mediaAdvance, …}` treated as free-form tenant charges | Constrained by **art. 9 ust. 5–6 u.o.p.l.**: apart from czynsz only **opłaty niezależne** (media, and only without a direct supplier contract) may be charged. `adminFee` is *not* an opłata niezależna → resolution in §2(a). | LAW §1 |
| C11 | External system = "BNP Paribas" (PM model) | Generalize to **`BankSource` with lifecycle** (kind = `manualFile \| aggregatorConsent \| directApi`); sequence **MT940 upload first**, aggregator later, own AISP never (for now). GoCardless/Nordigen is **dead — do not build on it**. | BANK §3–5 |
| C12 | (Q4 assumption) separate deposit account | **No statutory trust-account regime exists** (2013 deregulation) — separation is contractual best practice; the **ledger, not the bank, is the source of truth for "whose money is this"**; commingling = OC/civil exposure, not statutory-accounting breach. | LAW §1 (u.g.n.), DEPOSIT §4 |
| C13 | (implicit) late-payment interest as a standard charge | Interest is a **right, not a duty** (KC art. 481); rate **floats** (NBP ref + 5.5 p.p.; ×2 max; no compounding) → store **rate windows**, model `InterestAccrued`/`InterestWaived` as explicit events; per-tenancy/owner toggle. | LAW §1/§4.8 |
| C14 | **F4** owner/pass-through split | Sharpened: the deposit is the **owner's debt**, manager holds purely as agent; interest earned on the deposit account **accrues to the owner** (Belka at source), tenant's only statutory upside is valorization. | DEPOSIT §4, SN III CZP 58/02 |

---

## 2. Cross-report conflicts — resolved positions

### (a) The component taxonomy — one coherent model

**The four pulls:** ① art. 9 ust. 5–6: only czynsz + opłaty niezależne chargeable; adminFee legally sits inside czynsz [LAW §1] · ② PM v1.1: tenant quoted **one monthly total**, optional `{rent, adminFee, mediaAdvance}` breakdown · ③ deposit valorization base = **czynsz** component only [DEPOSIT §1] · ④ ryczałt excludes media/administracja **only when the written contract literally splits** czynsz vs opłaty and the landlord passes them at cost [LAW §1].

**Position — a three-layer taxonomy, contract wording as the switch:**

| Layer | Content | Semantics |
|---|---|---|
| **Tenant sees** | ONE monthly total (quote, psychology) | v1.1 mandatory total stands unchanged |
| **Contract says** (load-bearing legal layer) | Explicit split: **(1) czynsz najmu** · **(2) opłaty administracyjne** the tenant *expressly assumes* (contractual pass-through — the art. 9 mitigation; lawyer P4 confirms wording) · **(3) zaliczki na opłaty niezależne** (media, at actual cost, only where no direct supplier contract — art. 9 ust. 6) | New tenancy flag: `componentSplitInContract: bool` |
| **Ledger components** (fixed dimension) | `rent \| adminFee \| mediaAdvance \| deposit \| repairRecharge \| interest` | Dimension on charge lines/entries, never separate accounts [LEDGER §1.1] |

**Projection bases (each projection names its base explicitly):**

| Projection | Base | Rule |
|---|---|---|
| Ryczałt (F5) | `rent` allocations received (+ forfeited deposit, + holdover comp) | media/adminFee excluded **only if** `componentSplitInContract`; else see collapse rule |
| Deposit valorization (F1) | contractual czynsz = `rent` component | excludes adminFee/mediaAdvance — lawyer P5 confirms |
| Owner income (F4) | `rent` | adminFee + mediaAdvance = pass-through liabilities, never owner income |
| Pass-through settlement | `adminFee` → wspólnota; `mediaAdvance` → true-up vs actual (art. 9 ust. 2, both directions, written zestawienie on every advance increase) | LAW §1, doc rows 5–6 |
| Arrears board / F6 | all components, per charge period | full-periods-unpaid counter (C8) |

**Collapse rule:** contract without a split → the whole amount is posted as a single `rent` line — fully taxable AND fully valorizable — with an ACL warning at reservation: *"no contractual split: entire amount enters the ryczałt base and the deposit valorization base"* [LAW §4.2]. **Warn-gate:** any tenant-facing charge component that is neither czynsz nor media and lacks the contractual-assumption clause is flagged (art. 9 ust. 5) [LAW §1]. Fallback if the lawyer rejects the adminFee pass-through construction: fold adminFee into `rent` at contract level; track the wspólnota cost as an **owner expense**, not a tenant charge.

### (b) Ledger account taxonomy vs law/deposit requirements

| Tension | Position |
|---|---|
| LEDGER §1.1 has one `assets:bank:{bankSourceId}` list; DEPOSIT §4 demands **one dedicated deposit account per owner entity** + per-owner three-way check | Compatible — keep the **one CoA**; bank accounts carry dimensions `{ownerId, purpose: operating\|deposit}`. The deposit three-way check runs **per owner**: `bank(deposit acct of O) == Σ liabilities:deposit for O's tenancies in HELD == Deposit Register`. Global trust equation stays as the umbrella (§3.1). |
| LEDGER models valorization as "formula at settlement, not a running balance"; DEPOSIT §4.4 wants a **headroom warning** (valorized obligations can exceed cash held) | Both: ledger holds **nominal** liability; a **projection** computes Σ current valorized refunds (replay `DepositCharged.multiplier` × rent history) vs deposit-account balance → top-up prompt before `DepositSettlementDue`. No new events needed [DEPOSIT §6 policy 2]. |
| C7 migration: LEDGER's `liabilities:deposit:{tenancyId}` has no notion of *which owner owes it* after transfer | Add dimension **`debtorOwnerId`** on deposit liabilities. Migration moves cash + liability only on consent (`DepositObligationTransferred`); unconsented rows appear as **`legacy-liability`** on the *old* owner's statement. |
| LEDGER §1.3 hesitates: deposit as receivable at activation vs AppFolio cash-side-only | **Receivable at activation** (Dr receivable[deposit] / Cr liabilities:deposit) — the board must show "deposit unpaid" and handover can be conditioned on payment (art. 6 ust. 1). Confirm with PO (§4 D11). |
| Management fee: LEDGER books `revenue:managementFee` in-system; session Q5 says the faktura is possibly external; LAW §3B: in fundacja books the fee is a cost | Both: the ledger **accrues** the fee (`ManagementFeeCharged`, owner-statement deduction — the management split ledger) regardless of where the faktura is issued; store an optional `fakturaRef`. `liabilities:vatOutput` stays dormant unless fee invoicing moves in-system. |
| DEPOSIT `Deposit` aggregate vs LEDGER `DepositAccount` aggregate | **Same thing — merged** as `Deposit` (per tenancy) with DEPOSIT §6's richer command/event set; the F2 `deposit` charge line references it (no duplication). |

### (c) Other conflicts found

| # | Conflict | Position |
|---|---|---|
| c1 | **KC 481 interest rate:** LAW says **9.25%**/yr (NBP ref 3.75% + 5.5 p.p., since 5.03.2026); DEPOSIT §3 says "11.25%/yr in 2026" (implies NBP 5.75% — a stale figure) | LAW's component-derived figure wins (**9.25%**). Moot for design: rates are stored as **time-bounded windows** (C13); verify current NBP ref at build time. |
| c2 | **Allocation order:** KC 451 lets the creditor take **interest before principal, oldest first** [LAW §1]; LEDGER §3.3 recommends oldest-dueDate-first but leaves **rent-first vs rent-last within a due date** open (owner income sooner vs pass-through protection) | Genuine PO decision → §4 D1. Recommended default there: oldest due date first; within a date **interest → mediaAdvance → adminFee → repairRecharge → rent (rent last)** — protects the zarządca's pass-through obligations; three-level override (global/property/tenancy) makes it data, not code [LEDGER §3.3]. |
| c3 | **Owner statement cadence:** F4/session leave it open; LAW doc row 9 + §3A require the pack **by ~the 10th** so the owner pays tax by the **20th** | Monthly, generated by the ~8th–10th; `OwnerStatementPrepared` then `RyczaltPackPrepared` (or one combined pack) per owner, per owner-period (`taxRegime` effective-dated — mid-month transfer = two part-month packs [LAW §3]). |
| c4 | **"Reconciliation is against invoices"** (session Q5) vs LEDGER's charge-centric allocation (F2) | Reconcile **against charge lines**; invoices remain **derived documents** (F2). Q5's "create up to X" button = pre-generate future charges + their invoice documents, then allocate — same mechanics as auto-consuming `unapplied` credit [LEDGER §1.2 variants]. Faktura/rachunek/pokwitowanie deadlines per LAW §2 rows 1–4 hang off the documents, not the ledger. |
| c5 | **Deposit-as-last-month's-rent** (unlawful unilateral practice) vs arrears board honesty | No special state: it *is* arrears + accruing interest [DEPOSIT §3]; but the board should **recognize the pattern** (final month + HELD deposit ≥ arrears) and suggest settlement over escalation. |

---

## 3. Consolidated pre-storming brief

### 3.1 Chart of accounts (one CoA + dimensions; the "five ledgers" are filtered projections) [LEDGER §0.1, §1.1]

Dimensions available on accounts/entries: `tenancyId, propertyId, unitId, ownerId, component, bankSourceId, purpose, debtorOwnerId`.

| Account pattern | Normal | Ledger view | Notes |
|---|---|---|---|
| `assets:bank:{bankSourceId}` | Dr | — | dims `{ownerId, purpose: operating\|deposit}`; one per feed; reconciliation anchor; fundacja account = new BankSource |
| `assets:receivable:{tenancyId}` | Dr | **Tenancy** | component is a dimension on lines, not sub-accounts |
| `liabilities:unapplied:{tenancyId}` | Cr | Tenancy | cash received ≠ allocated; doubles as prepayment/credit balance |
| `liabilities:deposit:{tenancyId}` | Cr | **Deposits** | nominal only; dims `{debtorOwnerId}`; valorization = projection |
| `liabilities:passthrough:admin:{tenancyId}` | Cr | Tenancy (pass-through) | owed onward to wspólnota; never owner income |
| `liabilities:passthrough:media:{tenancyId}` | Cr | Tenancy (pass-through) | drained by true-up (`MediaSettlementPrepared`, charge or credit) |
| `liabilities:ownerPayable:{ownerId}` | Cr | **Owner split** | the owner statement IS this account's statement; co-owner % at posting time |
| `expenses:repairs:{propertyId\|unitId}` | Dr | **Repairs** | owner-borne or recharged (`repairRecharge` receivable) |
| `revenue:managementFee:{propertyId}` | Cr | **Management split** | operator's only P&L line |

**Architecture:** domain events are the source of truth; versioned **posting rules** (pure, data-like, testable) map each event to a balanced `LedgerTransaction` (Σ Dr = Σ Cr enforced at construction). Every transaction is bitemporal: `effectiveDate` + `recordedAt`. Corrections = compensating reversals, never edits — same doctrine at both layers [LEDGER §0.2, §4].

**Trust equation (daily invariant, alertable):** three-way form — `bank statement balance == ledger bank account == Σ(ownerPayable + deposits + unapplied + pass-through)`, plus the **per-owner deposit sub-check** and the **valorization headroom check** (§2b). Any drift = bug or unposted fact [LEDGER §1.1, DEPOSIT §4.3–4.4].

### 3.2 Candidate aggregates

| Aggregate | Key invariant / purpose | Source |
|---|---|---|
| **TenancyLedger** (per tenancy) | Charge lines + credit application; allocated ≤ charged per line; components from the fixed taxonomy | LEDGER §2 |
| **IncomingPayment** (per bank line) | Lifecycle of one credit; never allocated beyond its amount; **owns the reconciliation match** (no separate ReconciliationMatch aggregate — N:1 handled by per-payment slices) | LEDGER §2–3 |
| **BankSource** | Feed lifecycle: `Pending → Active → RenewalDue(T−14d) → Expired/Revoked/Superseded → Archived`; kinds `manualFile\|aggregatorConsent\|directApi`; idempotent import (line hash) | BANK §5 |
| **Deposit** (per tenancy; merged) | §3 DEPOSIT state machine incl. draw-down branch + migration; multiplier snapshot; cap warn per legalForm (C1) | DEPOSIT §6, LEDGER §2 |
| **OwnerAccount** (per owner) | Payable + statements + payouts per % shares; **OwnerStatement is a derived doc + soft period close, not an aggregate**; `taxRegime` effective-dated | LEDGER §2, LAW §3 |
| **RepairBill** | Supplier/repairman bills; who-pays decision (owner vs recharge); nota obciążeniowa vs refaktura basis | LEDGER §2, LAW §2 row 8 |

**Process managers:** Deposit Settlement (settlement due → valorization → prefilled deductions → return), **Deposit Migration** (on `PropertyOwnershipChanged`: per HELD deposit consent + cash movement; C7), Consent Renewal (180-day cycle nudges), Media Settlement (true-up from protocol readings + supplier invoices), Monthly Charging (rent-day schedule per tenancy), Owner Reporting cycle (statement → ryczałt pack → soft close).

### 3.3 Candidate events by cluster

**Charging** — `ChargePosted {chargeId, lines[{component, amount}], postingDate, dueDate, origin: recurring|oneOff|trueUp|recharge}` · `ChargeReversed {reversesChargeId, reason}` · `MediaAdvanceChangeScheduled` (+ mandatory written zestawienie doc, LAW row 5) · `MediaSettlementPrepared {period, meterReadings, resultingChargeId|creditId}` · `InterestAccrued {rateWindowRef}` / `InterestWaived` · `RepairBillRecorded` · `RepairChargedToOwner` / `RepairRechargedToTenancy {basis: damageNote|serviceRecharge}` · `InvoiceRequested` / `InvoiceIssued` (derived; VAT-zw. basis art. 43 ust. 1 pkt 36 on the doc) · `ReceiptIssued` (pokwitowanie).

**Ingestion** — `BankSourceRegistered {iban, ownerId, purpose, kind}` · `BankConsentGranted {validUntil ≤180d}` · `ConsentRenewalRequested` · `BankConsentRenewed/Expired/Revoked` · `BankSourceSuperseded` (fundacja switch) · `BankSourceArchived` · `StatementFetched {hash}` / `StatementFetchFailed {reason: consentExpired|bankDown|rateLimited}` · `StatementOverdueDetected` (manual-file nag) · `TransactionIngested {bookingDate, valueDate, amount, counterparty, rawTitle}`.

**Reconciliation / allocation** — `PaymentMatchSuggested {candidates, matchedBy: rule1..4}` · `PaymentMatchConfirmed` · `PaymentAllocated {allocations[{tenancyId, chargeId, component, amount}]}` · `PaymentAllocationAmended` · `OverpaymentRetainedAsCredit` · `PaymentMarkedNonTenant {category}` · `PaymentReversed {bankReasonCode}` (NSF → reopen receivables at original dueDates). Matching ladder: exact reference+amount → reference-only (suggest) → heuristic (payer-account memory, never auto) → manual queue; store provenance [LEDGER §3.2].

**Deposit lifecycle (incl. migration)** — `DepositCharged {nominal, rentAtSigning, multiplier, legalForm, capCheckResult}` · `DepositReceived` (partials) · `DepositDrawnDown {legalBasis: art19f7|contractClause}` · `DepositReplenished` · `DepositSettlementDue {deadline = max(vacateDate, moveOutProtocolDate)+1m}` · `DepositValorizationComputed {rentAtReturn, floorApplied}` · `DeductionClaimed {type, evidenceRefs{moveInProtocol, moveOutProtocol, photos, invoiceOrKosztorys}}` (warn on missing evidence) · `DepositSettlementPrepared` (itemized statement doc) · `DepositReturned` / `DepositPartiallyWithheld` · `DepositForfeited` (→ tax event) · `DepositReturnOverdue` (KC-481 interest estimate) · `DepositDisputed` · `DepositObligationTransferred {fromOwner, toOwner, consentDoc, cashMovedRef}` · `DepositRolledOver {newTenancyId, settlementDelta, newCapCheck}` [DEPOSIT §6].

**Owner reporting / tax** — `ManagementFeeCharged {basis, rate}` · `OwnerExpenseRecorded {category, docRef}` · `OwnerStatementPrepared {period, opening, income[], expenses[], closing}` · `OwnerPayoutInitiated/Confirmed` (co-owner % split) · `RyczaltPackPrepared` (contents: LAW §3A hand-off 1–5) · `PeriodSoftClosed` / `StatementRegenerated (v2)` · `RetentionHoldSet/Released` (financial hold for PII lookaside) · `RentMirrorReportExtracted` (art. 186a, half-yearly) · `VatLimitWarningRaised` (rolling-12m vs 240k) · `TaxRegimeChanged {ryczaltPIT|fundacjaRodzinnaCIT|fundacjaCIT, effectiveFrom}`.

**Corrections** — `ChargeReversed` · `PaymentReversed` · `PaymentAllocationAmended` · policy on `TenancyEnded{errorAnnulled}` → reverse **all** charges of the phantom tenancy, received money → `unapplied` (refund or re-match); ryczałt projection sums net of reversals — phantom rent falls out with zero special-casing [LEDGER §4]. Post-close corrections warn + force effectiveDate into the open period or regenerate v2.

### 3.4 Candidate policies

| Trigger | Action |
|---|---|
| `TenancyActivated` | Post deposit charge (multiplier snapshot, cap warn per C1) + first rent charge, per-component lines |
| Rent-day schedule / `RentChangeScheduled` −1d | Post monthly `ChargePosted` (postingDate ≠ dueDate) |
| Daily 06:00 | Fetch every Active API source (≤4/day); manual sources idle N days → `StatementOverdueDetected`; run trust-equation + deposit three-way + valorization-headroom checks |
| `TransactionIngested` | Run matching ladder → auto-allocate (rule 1) or queue suggestion |
| Consent expiry −14d | `ConsentRenewalRequested` → owner nudge (email/SMS + reconnect link) |
| Charge period fully unpaid ×3 | `ArrearsReached3Periods` (full periods, C8) → future termination process (warning-letter step) |
| `HandoverProtocolRecorded(moveOut)` ∧ vacateDate | `DepositSettlementDue` + auto valorization + prefilled settlement (arrears + protocol deltas + media true-up) |
| Settlement deadline −7d / passed | Warning / `DepositReturnOverdue` (litigation-risk red) |
| `DepositForfeited` / `DepositDrawnDown` | Post retained amount as rental income, that month, cash basis (C9) |
| `PropertyOwnershipChanged` | Start Deposit Migration Process; effective-date `taxRegime`; register successor BankSource |
| `PaymentAllocated` | `ManagementFeeCharged` (if D2 = % of collected) |
| Month-end / ~8th | `OwnerStatementPrepared` → `RyczaltPackPrepared` → `PeriodSoftClosed` |
| Ledger references a contact | `RetentionHoldSet` until max(taxRetentionEnd ≈ 5y+, civilPrescriptionEnd) [LAW §1 retention] |

### 3.5 Read models

1. **Arrears board** (green/golden/yellow/red/bright-red) — per tenancy: open charge lines by component, days late, **full-periods-unpaid counter** (C8 → F6 computable), unapplied credit, deposit-as-last-month pattern hint (§2c5).
2. **Owner statement** — opening → categorized income (rent only) → categorized expenses (repairs, **management fee**) → disbursement → closing; pass-through shown as footnote, never income; legacy deposit liabilities post-migration (C7).
3. **Ryczałt monthly pack** — per owner, cash-basis by bank value date: received-`rent` register (+ forfeited deposits), YTD vs 100k with 8.5/12.5 computation (advisory), reversals/corrections, copies of faktury/rachunki, flags (interest received, compensations) [LAW §3A].
4. **Trust equation check** — three-way global + per-owner deposit sub-check + valorization headroom (§3.1).
5. **Deposit Register** — per owner: tenancy, nominal, multiplier, current valorized refund, state, deadline; feeds the statutory-deadlines Timeline lane.
6. **Unmatched/suggested payments queue** — the manager's daily reconciliation screen.
7. **Bank source health** — consent validity, last fetch, failure reasons.
8. Rent-mirror extract (art. 186a) · VAT 240k rolling tracker · retention-hold register (PII lookaside gate).

---

## 4. Decision list for the product owner

| # | Decision | Options | Recommended default | Why / source |
|---|---|---|---|---|
| D1 | **Allocation order** within oldest-due-first | rent-first vs rent-last among components | Oldest dueDate first; within a date: **interest → mediaAdvance → adminFee → repairRecharge → rent (last)**; three-level override global/property/tenancy, stored as data | KC 451 (interest first); rent-last protects zarządca pass-through obligations [LEDGER §3.3, LAW §1] |
| D2 | **Management-fee basis** | % of collected rent on allocation vs % of charged rent monthly vs flat | **% of collected rent, charged on allocation** (cash-safe: no fee on money never received) | LEDGER open Q2 |
| D3 | **Interest charging** | off / per-tenancy toggle / always | Build the engine (rate windows), **default OFF**; explicit `InterestWaived` when arrears settle without it — waiving is the recorded business norm | LAW §4.8 (right, not duty) |
| D4 | **Deposit account structure** | one global deposit account vs per-owner | **One deposit bank account per owner entity** (fundacja gets its own at transfer); per-owner three-way check | DEPOSIT §4; lawyer P7 on manager-held alternative |
| D5 | **Media true-up cadence** | annual / semi-annual / at tenancy end only | **Annual + always at tenancy end** (move-out protocol), per-property configurable; written zestawienie on every advance increase (mandatory, LAW row 5) | art. 9 ust. 2; PM hotspot #13 (ask who does it today) |
| D6 | **Bank route sequencing** | file-first vs aggregator-first vs own AISP | **MT940 upload first (days, 0 zł)** → aggregator adapter on the same pipeline (Enable Banking for self-serve start, Kontomatik for CEE/BNP-GOonline certainty — decide after sandbox trial) → own AISP never at this scale | BANK §4 |
| D7 | **Fundacja timing implications** | build migration now vs fast-follow | Model the **fields now** (`taxRegime`, `debtorOwnerId`, `legalForm` per tenancy), ship Deposit Migration Process as fast-follow; **do not schedule the actual transfer without counsel** — 2026 relegislation pending (P1) | LAW §3B, DEPOSIT §5 |
| D8 | **KSeF relevance** | ignore / build now / prepare seam | **Prepare the seam**: separate rendering (PDF for consumers) from the fiscal channel; flag business (NIP) tenants on Contact; KSeF adapter only when the first business tenant demands a faktura (2027 horizon) | LAW §2 row 4, §4.5 |
| D9 | **Gmina art. 186a rent-mirror report** | ignore vs build projection | **Build** `RentMirrorReportExtracted` — statutory zarządca duty, half-yearly, data already exists in PM; cheap win | LAW §1 (u.g.n.) |
| D10 | **Financial-hold on PII lookaside** | erase freely vs hold | **`RetentionHoldSet/Released`** on the ledger side; contact rows referenced by ledger docs erasable only after max(taxRetentionEnd ~5–6y, civilPrescriptionEnd); dead leads without financial trail stay freely erasable | LAW §1 retention, §4.10 |
| D11 | **Deposit accrual** | receivable at activation vs cash-only recognition | **Receivable at activation** — board shows "deposit unpaid"; confirm it matches manager thinking | LEDGER §1.3, open Q3 |
| D12 | **Pass-through paid onward from connected account?** | model outbound payments vs informational-only | **Informational-only for MVP** (owner pays wspólnota/suppliers; liabilities drain via owner-statement footnote); outbound payments later if the manager starts paying | LEDGER open Q4 |
| D13 | **Contract-split enforcement** | optional breakdown vs near-mandatory | **Near-mandatory warn**: reservation without a contractual split triggers "entire amount taxable + valorizable" (collapse rule §2a) | LAW §4.2 |
| D14 | **VAT 240k tracker** | ignore vs rolling warn | **Build the rolling-12-month owner-turnover warning** (exempt rent counts toward the limit; media re-invoiced per C-42/14 too) | LAW §4.3 |
| D15 | **Soft period close** | none vs warn+v2 | **Warn + regenerate as v2** after a statement/pack is sent; post-dated corrections forced into the open period | LEDGER §4 |

---

## 5. Questions for professionals (merged, deduped, ranked)

**Tier 1 — before the fundacja transfer (time-critical):**

| # | Who | Question | Source |
|---|---|---|---|
| P1 | Tax + lawyer | **Fundacja transfer timing vs the vetoed-and-returning 2026 tightening**: lock-up possibly counted from assets contributed after 31.08.2025; only *direct long-term residential* letting may stay CIT-exempt → keep the zarządca an **agent, never an intermediary tenant**; confirm rodzinna vs zwykła (regime choice drives the whole accountant hand-off format) | LAW §3B/C, §4.6; PM hotspot #17 |
| P2 | Lawyer | **Deposit migration construction**: przejęcie długu (KC 519/522) via tripartite annex per tenancy — confirm; what when a tenant refuses/is unreachable (can cash still move; how to paper the old owner's residual liability); does aport/darowizna vs sale change the art. 678 analysis? | DEPOSIT §5, Q4 |
| P3 | Lawyer | **Re-signing zwykły → instytucjonalny**: old deposit settled (valorized!) or rolled over by agreement; new 6× cap vs new rent; draw-down branch activates | DEPOSIT §5 |

**Tier 2 — before contract templates / first instytucjonalny agreement:**

| # | Who | Question | Source |
|---|---|---|---|
| P4 | Lawyer + tax | **Component-split wording** (the §2a linchpin): is adminFee as an expressly-assumed contractual pass-through defensible under art. 9 ust. 5–6, and what template wording simultaneously secures the ryczałt media/admin exclusion (KIS line: literal czynsz-vs-opłaty split, at-cost intermediary)? | LAW §1, §4.2 |
| P5 | Lawyer | **Valorization base**: "czynsz obowiązujący w dniu zwrotu" after the tenancy ended = last contractual rent? Confirm base excludes adminFee/mediaAdvance when rent is one component of a bundled total | DEPOSIT Q2 |
| P6 | Lawyer | Valorization for okazjonalny/instytucjonalny (art. 19e/19j incorporate art. 6 ust. 3 despite market practice) — and can a contract exclude it? | DEPOSIT Q1 |
| P7 | Lawyer | May the **zarządca hold deposits in the manager's own account** on behalf of owners — u.g.n./AML/tax exposure, whose insolvency risk? (vs D4's per-owner accounts) | DEPOSIT Q8 |
| P8 | Lawyer | Mid-tenancy **draw-down for zwykły via contract clause** — enforceable? Replenishment clause ("uzupełnienie w 14 dni pod rygorem wypowiedzenia") valid under u.o.p.l.? | DEPOSIT Q5 |
| P9 | Lawyer | Fractional multiplier (deposit ÷ rent = e.g. 1.2) — is the derived-decimal reading of "krotność" safe? | DEPOSIT Q3 |

**Tier 3 — operational practice:**

| # | Who | Question | Source |
|---|---|---|---|
| P10 | Lawyer | Settlement mechanics: unilateral itemized settlement + partial refund safe, or obtain tenant's signature (and does signing waive claims)? | DEPOSIT Q9 |
| P11 | Lawyer | Holdover odszkodowanie (art. 18) deductible from the deposit for zwykły as "należność z tytułu najmu w dniu opróżnienia"? | DEPOSIT Q10 |
| P12 | Lawyer | KC 677 one-year prescription vs late-found damage — should the system warn "claim within 12 months of return"? | DEPOSIT Q11 |
| P13 | Lawyer | Tenant death without an art. 691 successor: return to whom/when (heirs without stwierdzenie; złożenie do depozytu sądowego KC 467); does the 1-month clock even start? | DEPOSIT Q6 |
| P14 | Lawyer + tax | Interest earned on the segregated deposit account — cleanly the owner's? Any duty to account to tenants; fundacja tax treatment | DEPOSIT Q7 |
| P15 | Tax | Confirm: retained/drawn-down deposit = przychód z najmu **in the month of retention** (cash basis) for the F5 pack; treatment of received **odsetki** and damage compensations | DEPOSIT §4, LAW §3A |
| P16 | Tax | VAT sleepers: 240k limit monitoring duty (exempt rent + media count), C-42/14 media re-invoicing rates (8%/23%), business tenant on a residential unit = 23% (interpretacja ogólna 8.10.2021) | LAW §4.3–4.4 |
| P17 | Tax | KSeF confirmation: B2C faktury outside KSeF, ≤10k zł/month transitional to 31.12.2026, business-tenant path from 2027 | LAW §2 row 4, §4.5 |
| P18 | Tax | Quarterly ryczałt option worth electing? Spouses: joint 100k vs 200k-single-spouse statement | LAW §3A |

---
*Every unmarked statute reference is u.o.p.l. Not legal advice; §5 exists precisely because of that.*
