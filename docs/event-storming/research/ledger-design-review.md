# Ledger Design Review — Double-Entry, Trust Accounting, Reconciliation

**Date:** 2026-08-03 · **For:** Accounting session (builds on `session-2026-08-03-accounting.md` Phase 1 and PM model v1.1 §9 / F1–F7)
**Question answered:** how to get "as much double-entry rigor as possible" (Q1) into an event-sourced Accounting core that is *not* the official books, across the five requested ledgers: tenancies, repairs, deposits, owner split, management split.

---

## 0. Headline recommendations

1. **One ledger, five views.** Do NOT build five physically separate ledgers. Build **one chart of accounts with dimensions** (`tenancyId`, `propertyId`, `ownerId`, `component`); the five "ledgers" are projections filtered by account class + dimension. Every industry system reviewed (Modern Treasury, Square Books, TigerBeetle, beancount/hledger, AppFolio) converges on this: sub-ledgers are *queries*, not silos — that is what makes the trust equation (§1.3) checkable at all.
2. **Ledger as a strictly-derived projection over domain events.** Domain events (`ChargePosted`, `PaymentAllocated`, …) are the source of truth; **posting rules** (Fowler) translate each into a balanced `LedgerTransaction` (N entries, Σdebits = Σcredits, enforced at write). This is Fowler's own advice — "Event Sourcing works particularly well with Accounting Entry" — and it keeps double-entry rigor without making the ledger a second write-model to keep in sync.
3. **Charges accrue; tax reports stay cash-basis.** The ledger is accrual (receivable at charge time — feeds the arrears board and F6). The ryczałt report (F5) is a *separate projection over `PaymentAllocated` events* (cash received, rent component only, net of reversals) — accrual ledger and cash-basis tax never fight.
4. **Money received ≠ money allocated.** Bank credit lands in an **unapplied-cash liability** first; allocation to charge lines is a distinct, correctable act. This one split cleanly solves partial payments, prepayments, one-transfer-many-charges (F3), and NSF reversal.
5. **Corrections = reversals, never edits** — the same rule at both layers (immutable events, immutable ledger entries). `errorAnnulled` (F7), NSF, and mis-allocations are all the same mechanism.

---

## 1. Recommended account structure

### 1.1 Chart of accounts

Trust-style ledger (we hold/track other people's money): **cash assets must always equal the sum of the money-owed liabilities** — the operator's only P&L line is management-fee revenue. Account naming beancount-style (hierarchy = free dimensions), instance-per-dimension like Modern Treasury's "one User Balance account per customer".

| Account (pattern) | Normal | The "five ledgers" mapping | Notes |
|---|---|---|---|
| `assets:bank:{bankSourceId}` | Dr | — | One per bank source (owner acct, deposit acct, future fundacja acct — Q4 lifecycle). Mirrors real statements; the reconciliation anchor. |
| `assets:receivable:{tenancyId}` | Dr | **Tenancy ledger** | Tenant debt. Component (`rent, adminFee, mediaAvance, deposit, repairRecharge, interest`) is a **dimension on entries/charge lines**, not more accounts — per-component balances are projections (F2 taxonomy). |
| `liabilities:unapplied:{tenancyId}` | Cr | Tenancy ledger | Cash received, not yet allocated. Doubles as the **tenant credit / prepayment balance**. |
| `liabilities:deposit:{tenancyId}` | Cr | **Deposit ledger** | Nominal amount held. Valorization (F1) is a *formula applied at settlement*, not a running balance — post the top-up as an owner-funded expense entry when refunding. |
| `liabilities:passthrough:admin:{tenancyId}` | Cr | Tenancy ledger (pass-through) | Admin fee collected, owed onward to the wspólnota/administrator. Never owner income (F4). |
| `liabilities:passthrough:media:{tenancyId}` | Cr | Tenancy ledger (pass-through) | Media advances held; true-up (`MediaSettlementPrepared`) debits/credits this vs a new charge or credit. |
| `liabilities:ownerPayable:{ownerId}` | Cr | **Owner split ledger** | The owner statement IS this account's statement: opening balance → categorized Cr (rent income) → categorized Dr (expenses, mgmt fee) → disbursement → closing (F4). Multi-owner: split per `PropertyOwnershipChanged` % at posting time. |
| `expenses:repairs:{propertyId|unitId}` | Dr | **Repairs ledger** | Supplier/repairman bills (Q5). Funded by Dr here / Cr `ownerPayable` (owner-borne) or recharged: Dr `receivable:{tenancyId}` (component `repairRecharge`). |
| `revenue:managementFee:{propertyId}` | Cr | **Management split ledger** | Operator's own revenue (F4). Its statement = the management split. |
| `liabilities:vatOutput` *(if needed)* | Cr | Management ledger | Only if management-fee faktury end up in-system (Q5 says possibly outside). |

**Trust equation (the daily invariant, projected + alertable):**
`Σ assets:bank = Σ unapplied + Σ deposit + Σ passthrough + Σ ownerPayable − Σ receivable-not-yet-collected-portion-of-bank…` — in practice check the AppFolio/Buildium **three-way form**: bank balance (statement) = ledger cash account = Σ(owner payables + deposits + unapplied + pass-through). Any drift is a bug or an unposted fact.

### 1.2 Worked example — tenant pays 3 000 zł = 2 400 rent + 400 admin + 200 media

**T1 — monthly charges posted** (Tenancy Accounting ACL fires on the rent day schedule; postingDate ≠ dueDate per F2):

| Entry | Dr | Cr | Component |
|---|---|---|---|
| receivable:{T} | 2 400 | | rent |
| ownerPayable:{O} | | 2 400 | rentIncome |
| receivable:{T} | 400 | | adminFee |
| passthrough:admin:{T} | | 400 | |
| receivable:{T} | 200 | | mediaAdvance |
| passthrough:media:{T} | | 200 | |

**T2 — bank statement line ingested** (transfer 3 000, reference `NAJEM/12/2026/A-KOW`):
`Dr assets:bank 3 000 / Cr unapplied:{T} 3 000`

**T3 — allocation** (auto: reference matched, amount = open charges):
`Dr unapplied:{T} 3 000 / Cr receivable:{T} 3 000` — allocation lines {rent 2 400, adminFee 400, mediaAdvance 200}. → Ryczałt projection records **2 400 received rent** for owner O this month (media/admin excluded from base, F5). Board goes green.

**T4 — management fee** (e.g. 10% of collected rent, policy on allocation):
`Dr ownerPayable:{O} 240 / Cr revenue:managementFee:{P} 240`

**Owner statement effect (F4 shape):** opening 0 → income: rent 2 400 → expenses: management fee −240 → **available 2 160** → disbursement `Dr ownerPayable 2 160 / Cr assets:bank 2 160` (split by co-owner % if applicable) → closing 0. The 600 of admin+media never appears as owner income — it sits in pass-through liabilities until paid onward (`Dr passthrough:* / Cr assets:bank`).

**Variants:** tenant pays 2 500 (partial) → T3 allocates by the order policy (§3.3), remainder of receivable stays open, board yellow. Tenant pays 36 000 (year ahead) → sits in `unapplied` as credit; monthly charges auto-consume it, **or** manager hits "create up to X" (Q5) to pre-generate charges+invoices and allocate at once — both are the same mechanics.

### 1.3 Deposit example
Deposit charge (on `TenancyActivated`): `Dr receivable:{T} [deposit] / Cr liabilities:deposit:{T}` — hmm, careful: the standard trust pattern (AppFolio) is **cash-side**: on receipt `Dr assets:bank:depositAcct / Cr liabilities:deposit:{T}`. Recommendation: post the deposit **charge** as receivable vs deposit-liability at activation (so the board shows "deposit unpaid"), receipt then clears the receivable via unapplied as normal. Settlement (F1): compute refund = max(nominal, multiplier × rent-at-return); deductions only with `HandoverProtocolRecorded` evidence → `Dr liabilities:deposit / Cr assets:bank` (refund part) + `Dr liabilities:deposit / Cr receivable` or `Cr ownerPayable` (deduction part) + valorization top-up `Dr ownerPayable / Cr assets:bank`.

---

## 2. Aggregate candidates + event names

Two-layer design: **domain aggregates emit business events; a Posting Rules service (pure functions, versioned) maps each to a balanced LedgerTransaction projection.** The ledger itself needs no aggregate (it's derived); the balance invariant lives in the posting-rule output type (constructor refuses unbalanced sets — Square Books / Modern Treasury "min two entries, Σ=0 per transaction").

| Aggregate | Purpose / invariants | Key events |
|---|---|---|
| **TenancyLedger** (per tenancy) | Owns charge lines + credit application. Invariant: a charge line's allocated amount ≤ its amount; components from the fixed taxonomy. | `ChargePosted {chargeId, lines[{component, amount}], postingDate, dueDate, origin: recurring\|oneOff\|trueUp\|recharge}`, `ChargeReversed {chargeId, reason, reversesChargeId}`, `CreditApplied`, `InvoiceRequested` / `InvoiceIssued` (derived doc, F2), `ArrearsReached3Periods` (F6 — emitted by an arrears policy watching this stream), `MediaSettlementPrepared {period, meterReadings→, resultingChargeId\|creditId}` |
| **IncomingPayment** (per bank statement line) | Lifecycle of one bank credit; can never be allocated beyond its amount. | `BankTransactionIngested {bankSourceId, amount, valueDate, counterparty, reference, raw}`, `PaymentMatchSuggested {candidates[]}`, `PaymentMatchConfirmed`, `PaymentAllocated {allocations[{tenancyId, chargeId, component, amount}]}`, `PaymentAllocationAmended`, `OverpaymentRetainedAsCredit`, `PaymentMarkedNonTenant {category: ownerFunding\|supplierRefund\|other}`, `PaymentReversed {reason: NSF\|bankReversal, reversesAllocations: true}` |
| **BankSource** | Q4: multiple accounts with lifecycle. | `BankSourceRegistered`, `BankSourceActivated/Expired`, `StatementImportCompleted {lines, coverage[dateFrom,dateTo]}` (idempotency key = bank line hash) |
| **DepositAccount** (per tenancy) | F1 formula holder: cap warn per legalForm, clock from `vacateDate`. | `DepositCharged`, `DepositReceived`, `DepositSettlementDue` (inbound), `DepositSettlementPrepared {refund, valorizationTopUp, deductions[{evidence: protocolRef, amount}]}`, `DepositRefunded`, `DepositDeductionApplied` |
| **OwnerAccount** (per owner) | Owner payable + statements + payouts per % shares. | `OwnerStatementPrepared {period, opening, income[], expenses[], closing}`, `OwnerPayoutInitiated/Confirmed`, `ManagementFeeCharged {basis, rate, amount}`, `OwnerExpenseRecorded {category, docRef}` (repair bills etc.) |
| **RepairBill** | Supplier/repairman bills (Q5); who-pays decision. | `RepairBillRecorded {docRef, amount, target: property\|unit, repairRef?}`, `RepairChargedToOwner`, `RepairRechargedToTenancy {tenancyId}` |

*(Reconciliation "Match" is modeled inside IncomingPayment for 1-payment→N-charges; the rarer N-payments→1-charge case needs no extra aggregate — each payment allocates its slice against the same charge line.)*

---

## 3. Reconciliation state machine

### 3.1 States (per IncomingPayment)

```
ingested ──auto──► matched(auto) ─────────► allocated ──► [settled]
   │                                            ▲   │
   ├──heuristic──► suggested ──confirm──────────┘   └─► partiallyAllocated (credit remainder)
   │                   │reject
   ├──none──────► unmatched ──manual match──► allocated
   │                   └─► markedNonTenant (owner top-up, supplier refund…)
   └─ any state ──NSF/bank reversal──► reversed (allocations reversed, receivable reopens)
```

### 3.2 Matching ladder (run in order, stop at first hit — industry standard tiering)
1. **Exact:** normalized `paymentReference` matches a tenancy AND amount == Σ open charges (or == one open charge) → auto-match + auto-allocate. Expected the overwhelming majority — the reference is agreed in the contract.
2. **Reference-only:** reference matches, amount ≠ open charges → `suggested` with a proposed allocation (under: partial per order policy; over: rest to credit; "several months at once": propose the create-up-to-X flow from Q5). Human confirms — matches TigerBeetle's two-phase pending→post/void shape.
3. **Heuristic:** no/garbled reference → score candidates by amount ±, dueDate window, counterparty-name ≈ tenant contact, historical payer account number (learn the tenant's bank account from past confirmed matches — the strongest quiet signal). → `suggested`, never auto.
4. **Unmatched** → manager queue. Manual match teaches rule 3 (store payer-account → tenancy association).

Skip ML for MVP; deterministic rules + learned payer-account memory covers a small portfolio. Keep match provenance (`matchedBy: rule1..4|manual`) on the event for the audit trail and future tuning.

### 3.3 Allocation order policy (which charge gets paid first)
Configurable priority list of components with **global default → per-property → per-tenancy override** (steal Buildium's exact three-level scheme). Recommended Polish default, aligned with KC art. 451 (creditor may satisfy związane należności uboczne first, oldest debt first): **oldest dueDate first; within a due date: interest → repairRecharge → mediaAdvance → adminFee → rent** — but confirm with the domain expert: putting rent *last* protects pass-through obligations, putting rent *first* maximizes owner income and the ryczałt base sooner. Make it data, not code.

---

## 4. Corrections & reversals

- **Never mutate, never delete.** A correction is a new event whose posting rule emits a compensating LedgerTransaction (equal-opposite entries) carrying `reversesTransactionId`, plus optionally a corrected re-post. Identical doctrine in Modern Treasury, Square Books, TigerBeetle, Formance — and it is exactly event sourcing, so the two layers agree for free.
- **NSF / bank-reversed payment:** `PaymentReversed` → posting rule reverses the bank entry AND all allocation entries of that payment → receivable reopens at the same dueDates (arrears/board recompute naturally, `ArrearsReached3Periods` can re-fire). Optionally `ChargePosted{component: fee}` for an NSF fee (Buildium does this in one gesture). Note AppFolio's lesson: "NSF" flag ≠ literally insufficient funds — store the bank's actual reason code.
- **Mis-allocation:** `PaymentAllocationAmended` = reverse old allocation entries + post new ones. Cheap, frequent, safe — design the UI around it (expert-system stance: fix by re-doing, not by blocking).
- **`errorAnnulled` tenancy (F7):** policy on `TenancyEnded{errorAnnulled}` → `ChargeReversed` for every charge of the phantom tenancy; any received money moves to `unapplied` (then refund or re-match). The ryczałt projection sums allocations *net of reversals*, so the phantom rent falls out of the tax base with zero special-casing.
- **Bitemporality:** every LedgerTransaction carries `effectiveDate` (business day: dueDate/valueDate) + `recordedAt` (append time). Statements and the ryczałt report query by effectiveDate; audit by recordedAt. (Modern Treasury "effective dates"; matches the PM model's decided-vs-effective rent changes.)
- **Period close (lightweight):** after an owner statement or ryczałt report is *sent*, post-dated corrections into that period should warn and force effectiveDate into the open period (or regenerate the statement as v2). Not official books — a soft close, but without it owner statements silently change under people's feet.

---

## 5. What to steal from each source

| Source | Steal | Link |
|---|---|---|
| **Modern Treasury** (Accounting for Developers I–II, Scaling a Ledger I–V) | 3-object model (Account / Entry / Transaction, Σ=0 per transaction); account-per-customer instancing; immutability ("reverse, never edit"); effective vs posted dates; balance = sum of entries, cached not stored-authoritative | [Accounting for Developers II](https://www.moderntreasury.com/journal/accounting-for-developers-part-ii) · [Immutability](https://www.moderntreasury.com/journal/enforcing-immutability-in-your-double-entry-ledger) · [Scale a Ledger V](https://www.moderntreasury.com/journal/how-to-scale-a-ledger-part-v) |
| **TigerBeetle** | Two-phase transfers (pending → posted/voided) as the template for suggested→confirmed reconciliation; debit/credit as the *only* two primitives; corrections as linked reversal transfers | [Debit/Credit schema](https://docs.tigerbeetle.com/concepts/debit-credit/) · [Data modeling](https://docs.tigerbeetle.com/coding/data-modeling/) |
| **Square Books** | Ledger as an immutable append-only service consumed by many product domains; consistency "as a result of directly applying double-entry" | [Books](https://developer.squareup.com/blog/books-an-immutable-double-entry-accounting-database-service/) |
| **Formance Ledger / Numscript** | Multi-posting atomic transactions; posting *templates* as declarative, reviewable artifacts → our posting rules should be data-like and testable, one per domain event type | [Ledger intro](https://docs.formance.com/modules/ledger/introduction) · [Numscript](https://github.com/formancehq/numscript) |
| **beancount / hledger** | Hierarchical account names as free dimensions; balance assertions (assert bank balance == statement balance after each import — cheap three-way check); everything-is-plain-transactions | [beancount](https://beancount.github.io/docs/) · [hledger](https://hledger.org/hledger.html) |
| **Fowler, Accounting Patterns** | Account/Entry/Transaction + **Posting Rule** as the event→entries mapper; "link Accounting Entry to the Domain Event for the audit trail" — our exact two-layer architecture | [accounting.pdf](https://martinfowler.com/apsupp/accounting.pdf) · [Accounting Entry](https://martinfowler.com/eaaDev/AccountingEntry.html) |
| **AppFolio / Buildium trust accounting** | Trust equation & 3-way reconciliation (bank = ledger = Σ client sub-ledgers) as a standing alarm; prepayment held as liability; deposit as liability from receipt; NSF reversal + fee in one gesture; **configurable payment allocation order at global/property/lease levels**; tenant credit types | [AppFolio trust structure](https://www.apmhelp.com/blog/appfolio/how-trust-accounting-actually-works-inside-appfolio-the-full-structure) · [3-way rec in Buildium](https://www.apmhelp.com/blog/trust-accounting/three-way-reconciliation-buildium-balancing-bank-books-trust-accounts) · [Buildium custom payment allocation](https://www.buildium.com/blog/introducing-custom-payment-allocation/) · [Buildium payment reversal](https://help.buildium.com/hc/s/article/How-do-I-reverse-a-tenant-payment-1557495016983) · [Tenant credits](https://www.apmhelp.com/blog/buildium-when-should-you-use-each-type-of-tenant-credit) |
| **Reconciliation engines** (Oracle/HighRadius/Numeric et al.) | Tiered rules (exact → reference → fuzzy), 1:N/N:1/N:M match types, human-review queue for suggestions, feed confirmed matches back into rules | [Oracle matching rules](https://docs.oracle.com/en/cloud/saas/financials/24c/faipp/reconciliation-matching-rules.html) · [Numeric guide](https://www.numeric.io/blog/bank-reconciliation-automation) |
| **Event-sourced accounting literature** | "The ledger is the original event-sourced system": entries are events, balances are projections — license to make the ledger a projection, not a second source of truth | [Damtoft, ES & the History of Accounting](https://dev.to/dealeron/event-sourcing-and-the-history-of-accounting-1aah) · [Simple Sourcing](https://simplesource.io/simple_sourcing_event_sourcing.html) · [oskardudycz/EventSourcing.NetCore (Marten samples)](https://github.com/oskardudycz/EventSourcing.NetCore) |

### Anti-lessons (what NOT to copy)
- AppFolio users "waste hours reconciling ledgers against bank deposits because partial payments, NSF reversals and late fees post out of order" — our bitemporal effectiveDate + append-only ordering is the antidote; never re-sequence history for display without showing recordedAt.
- Buildium's *default* allocation ("largest balance first") is accounting-arbitrary — default to oldest-due-first per KC art. 451 instead; keep only their three-level override scheme.
- Don't adopt Formance/TigerBeetle as runtime dependencies for MVP — portfolio scale is tiny; steal the models, run on Postgres + the event store.

### Open questions for the session
1. Allocation-order default: rent-first or rent-last within a due date? (owner income vs pass-through protection — §3.3)
2. Management fee basis & timing: % of *collected* rent on allocation (recommended, cash-safe) or % of charged rent monthly?
3. Deposit charge accrual: post deposit as a receivable at activation (visible as "unpaid") — confirm this matches how managers think, vs cash-only recognition.
4. Do admin/media pass-throughs get *paid onward* from the connected account (then model outbound payments), or does the owner handle that outside (then pass-through liabilities are informational and drained by owner statement footnote)?
