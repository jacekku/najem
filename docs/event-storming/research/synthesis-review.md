# Synthesis Review — Triage of Legal & Comparable-Systems Findings

**Date:** 2026-08-03
**Inputs:** `property-management-domain-model.md` (authoritative model), `session-2026-08-03-property-management.md` (decisions), `polish-rent-law-review.md` (law), `comparable-systems-review.md` (products/OSS).
**Purpose:** one decision-ready list for the product-owner walkthrough. Findings from both reports are deduped; each item was checked against what the session already decided.

**Reading the triage — three cross-cutting positions taken here:**

1. **The expert-system philosophy survives the legal review.** The law binds the *parties*, not the record-keeping tool (law review 1.6 concedes this itself). Nearly every legal finding lands as: a typed field, a reserved event, a deadline prompt, or a **warn-and-confirm gate on the PM → Tenancy Accounting ACL** — not a wall inside PM aggregates. The no-overlap tenancy calendar stays the only hard invariant. The two "hard-ish" exceptions are gates on *auto*-behavior, not on the manager: (a) auto-activation of an okazjonalny tenancy should not fire while its notarial-declaration document set is missing (manager can still activate manually, warned); (b) the ACL should not *silently* auto-issue a deposit charge above the statutory cap or a unilateral-increase rent charge that violates art. 8a/9 — explicit manager confirm required, decision recorded.
2. **Where the reports conflict, money mechanics beat compliance workflows for MVP sequencing.** The comparable review's top finding (composite payment: rent + czynsz administracyjny + media) breaks reconciliation and owner reports — the MVP's stated pillars — from day one. The law review's workflow blockers (arrears termination, rent-increase notice procedure) only bite when a hostile termination/increase is actually attempted, and the arrears one *cannot* be built before Accounting exists. They are sequenced, not dropped (§D, §F).
3. **The law review overstates two findings** relative to the model as decided; corrections are noted inline (A2 severity, and deposit "unmodeled" vs the session's explicit deferral — hotspot #4).

---

## A. Model mistakes to fix now

The compiled model contradicts law or Polish-market reality. Each row: what's wrong → the concrete change.

| # | What's wrong | Concrete change | Source |
|---|---|---|---|
| A1 | `Tenancy` has no **legal form** — the root cause behind most legal findings: deposit cap (12×/6×/3×), rent-increase regime, termination rules, and the 14-day tax-registration clock all branch on it. Both reports flag it independently. | Add to `TenancyReserved`: `legalForm: {zwykly \| okazjonalny \| instytucjonalny}` + `term: {fixedTerm(endDate) \| indefinite}`. `legalForm` also seeds the checklist template (B1). | u.o.p.l. art. 6/19a/19f (law §0, 2.1) + comparable gap #5 |
| A2 | `negotiatedRent` is a **scalar** — but the tenant's monthly transfer is a composite: czynsz najmu (owner income) + czynsz administracyjny (wspólnota pass-through) + media advance. Reconciliation, the green/red arrears board, owner reports, and the ryczałt tax base all misreport on a scalar. | `TenancyReserved` carries a typed breakdown `{rent, adminFee, mediaAdvance}` (any component may be 0). `RentChangeScheduled` targets a component. TA charge commands carry line items per component (already "line items Y" in the session — only the taxonomy was missing). | Comparable gaps #1/#2/#7 + law 2.6 (art. 9 ust. 2 u.o.p.l., ryczałt base) — deduped |
| A3 | `RentChangeScheduled` treats all changes alike; for **unilateral increases under najem zwykły** art. 8a/9 impose written notice, 3-month delay, 6-month frequency, and a tenant refusal right. *Position: the law review's "legally impossible" (finding 1.1) overstates it — agreed/annex changes and contract-clause indexation (the common cases) are unconstrained. The fix is a distinction + warning, not a workflow.* | Add `changeType: {agreedChange \| unilateralIncrease \| indexation}`. ACL warn-and-confirm when a `unilateralIncrease` charge violates notice/frequency (per `legalForm`). Reserve `RentIncreaseRefusedByTenant` → ends tenancy (feeds A4 taxonomy). Existing day-before-charge policy unchanged. | u.o.p.l. art. 8a, 9 ust. 1b, 19c (law 1.1/2.4) |
| A4 | `TenancyEnded.reasonType` (`natural/terminated/error-annulled`) is too coarse to be legally meaningful — "mutual agreement", the main lawful early exit, is absent; landlord notice is a closed statutory catalogue. The *event stays a free record* (manager freedom, system-is-not-the-notary — law 1.2 concedes this); only the enum widens. | `reasonType: {agreementExpiry \| mutualAgreement \| tenantNotice \| landlordNotice \| rentIncreaseRefusal \| vacateDemand \| courtEviction \| errorAnnulled}` + add `vacateDate` (may differ from contractual end; starts the deposit clock, B3). Session already accepted a distinguishable reason type — this completes it. | u.o.p.l. art. 11, KC art. 673 §3 (law 1.2) |
| A5 | Okazjonalny **14-day registration clock** would be derived from `TenancyActivated`, which can fire late (checklist) — the deadline runs from lease commencement. Silent miss ⇒ owner loses the simplified-eviction regime. | Policy reads the deadline from the **agreement `startDate` on `TenancyReserved`** (field already exists — this is a one-line policy statement, not a new field). | u.o.p.l. art. 19b (law 1.5) |
| A6 | "Damage protocol" is a free-form checklist string — art. 6c makes the protokół zdawczo-odbiorczy **the legal basis of end-of-tenancy settlement**, and every Polish tool treats its meter readings as first-class. | Promote to `HandoverProtocolRecorded {tenancyId, type: moveIn\|moveOut, meterReadings[{meterType, value}], conditionNotes, photos(S3), signedDoc(S3), date}` — move-in feeds the Tenancy Start Process gate; move-out feeds deposit settlement + media true-up (F). S3 already in scope. | u.o.p.l. art. 6c/6e (law 1.4) + comparable gap #3 (meter readings) — deduped |

## B. Must model now

Missing concepts that bite the MVP or are legally required from day one.

| # | Item | Model change | Effort | Source |
|---|---|---|---|---|
| B1 | **Okazjonalny document set + registration tracking** — the three attachments (notarial submission-to-enforcement, replacement-premises indication, owner-consent) + registration proof; without registration the regime is void. | Typed **gating** checklist items seeded by `legalForm` (not free-form); events `NotarialDeclarationAttached`, `TenancyRegisteredWithTaxOffice {date, confirmationDoc}`, `ReplacementPremisesLost` (starts 21-day clock). Gate: auto-activation waits on the set; manual activation stays possible (warned). | Medium | u.o.p.l. art. 19a ust. 2–3, 19b (law 2.1); kluczo.pl okazjonalny guide |
| B2 | **Property compliance calendar** — art. 62 periodic inspections (annual gas/chimney/weather-exposed; 5-year electrical; flue cleaning) are the *manager's* statutory duty, fined if missed; smoke/CO detector deadlines (2026/2030) fold in. Entirely absent from the model. | Small `PropertyCompliance` concept: `InspectionCompleted {property, type, date, reportDoc, findings}` + next-due computation + overdue **prompt** (same mechanism as `TenancyEndingSoon` — expert-system consistent). Feeds the Property-level Timeline "problems" lane the expert already asked for. | Medium | Prawo budowlane art. 62/64; rozp. MSWiA 21.11.2024 (law 2.2, 2.8 — merged) |
| B3 | **Deposit: two PM-side slivers now** — *the deposit lifecycle stays deferred (hotspot #4 stands; the law review's "unmodeled" framing ignores the explicit deferral)*, but two items can't wait for the Accounting session: the capture point and the statutory return clock. | (1) `TenancyReserved` captures `depositMultiplier`/`depositAmount` (ACL warns vs `legalForm` cap on charge issuance). (2) End-of-Tenancy Process emits `DepositSettlementDue {tenancyId, deadline = vacateDate + 1 month}` as a typed checklist deadline item — the one deadline with litigation risk. Everything else → §F. | Small | u.o.p.l. art. 6 ust. 1/4, 19a ust. 4–5, 19f ust. 4–5 (law 1.3, 2.5) + comparable pinned-#4 note — deduped |
| B4 | **Tenancy documents** — "digitalize agreements" is a stated MVP core value, yet no event attaches the signed agreement, notices, or guarantor surety (written form required, KC art. 876 §2) to the Tenancy stream. | `TenancyDocumentAttached {tenancyId, docType: {agreement \| annex \| notice \| guarantorSurety \| other}, s3Ref, date}`. Doubles as the law review's "evidence vault" (3.2) — delivery-proof docs for art. 8a/11 notices land here too. | Small | Comparable gap #4 + law 3.2/3.8 — deduped |
| B5 | **RODO minimum for Contacts/Leads** — the "I'll start in a year" leads pool exceeds pre-contract basis; no retention/erasure concept exists anywhere; and an event-sourced Contacts stream needs an **erasure strategy decided before the store format ossifies** (crypto-shredding or PII lookaside). This last is an architecture decision that cannot be retrofitted cheaply. | `LeadRegistered` gains `{lawfulBasis, infoClauseServedAt, retainUntil}`; events `LeadDataErased`, `ContactRetentionExpired`; policy "lead inactive > N months ⇒ prompt/erase". **Decide the PII storage strategy now.** | Medium (mostly the architecture decision) | RODO art. 5/6/13/28 (law 2.7) |

## C. Cheap wins

Small cost now, avoids repainting later. All keep manager freedom.

| # | Item | Change (one line each) | Source |
|---|---|---|---|
| C1 | Explicit termination-notice event | `TerminationNoticeGiven {ground, noticeDate, effectiveDate, noticeDoc}` — resolves hotspot #12 (`TenancyEndDateSet`) *and* becomes the evidence hook for lawful `landlordNotice`/`tenantNotice` endings; feeds `TenancyEndingSoon`. | law 1.2/2.3; hotspot #12 |
| C2 | Indexation clause metadata | On Tenancy: `{indexationClause: {indexType, anniversaryMonth, cap}}` + yearly policy *proposing* a `RentChangeScheduled(changeType: indexation)` to the manager — `RentChangeScheduled` is already the right primitive (comparable's own words). | Comparable gap #9; kluczo.pl indeksacja |
| C3 | 10-year term check | Warn on `ReserveTenancy` when term > 10 years (KC art. 661; okazjonalny hard-capped at 10). One validation line. | law 1.6 |
| C4 | Repair duty hint | `RepairReported` gains `statutoryDutyHint: {landlord \| tenant \| negotiable}` per art. 6a/6b — so the Accounting session doesn't re-derive who pays. | law 2.9 |
| C5 | Reserved event names (naming only, no behavior) | `TenancyExtended`, `ArrearsWarningSent {cureDeadline}`, `RentIncreaseNoticeGiven`, `MediaSettlementPrepared` — so post-MVP flows slot in without renaming. | comparable #11; law 2.3/2.4; comparable gap #2 |
| C6 | Posting date vs due date | TA charge commands carry both dates explicitly (Buildium "posting day" pattern; the day-before policy already implies it). | Comparable lesson 4 |
| C7 | Legal-clock Timeline lane | The deadline-bearing events above (B1 registration, B3 deposit, B2 inspections, C1 notices) project into one "running statutory deadlines" lane on the already-planned Timeline read model — pure read-side. | law 3.1 |
| C8 | Management contract + OC policy docs | If the operator is a zarządca (see E1): store the written management agreement + current OC insurance policy with validity dates per owner relationship — document storage, no workflow. | u.g.n. art. 184b–186 (law 2.10) |

## D. Consciously skip for MVP

| # | Item | Rationale (one line) | Source |
|---|---|---|---|
| D1 | Prorated first/last month | Session decision stands; manual charge edit is the workaround — but comparable review is right that it generates corrections from day one: first fast-follow. | Comparable challenge #5 |
| D2 | Arrears Termination Process (full 3-periods → warning → cure → notice workflow) | *Position vs law review's 🟥: cannot precede the Accounting session that produces `ArrearsReached3Periods`; risk only materializes when a hostile termination is attempted, and the manager + lawyer execute it on paper anyway.* Events reserved (C1, C5); build with/after Accounting. | law 2.3 |
| D3 | Full rent-increase notice workflow (justification demands, court freeze) | ACL warning (A3) + notice document (B4) suffice; the system records, the notary notarizes. | law 2.4 |
| D4 | Configurable `TenancyEndingSoon` lead time | *Position on the reports' conflict: fixed 1 month is fine for natural expiry (comparable agrees, YAGNI stands); the law review's longer horizons ride on `TerminationNoticeGiven` (C1) when that flow is built — not on this timer.* | law 1.2 vs comparable challenge #4 |
| D5 | `TenancyExtended` / renewal flow | End+new + comment holds for MVP; name reserved (C5); ties into hotspot #2 (annexes) — ask experts first (E4). | Comparable gap #11 |
| D6 | Late fees / statutory interest | Arrears *visibility* is MVP, monetization isn't; charge model stays open to interest lines (F2). | Comparable gap #10 |
| D7 | Rent reminders, tenant portal, e-sign, screening, viewings/CRM, vendor management, listing syndication | All confirmed out by both reports; screening = buy (simpl.rent), never build. | Comparable gaps #12–#18 |
| D8 | Vacancy KPIs / pustostan digest | Raw events already captured; pure projection work later — `errorAnnulled` typing (accepted in session) is what keeps the stats honest. | Comparable gap #13 |
| D9 | CEEB heat-source declarations | Awareness checkbox at most; only bites when a boiler is replaced. | law 3.7 |

## E. Questions for the domain experts

Merged with the existing hotspot log (numbers referenced).

| # | Question | Why it matters now | Relates to |
|---|---|---|---|
| E1 | **Which legal setup is the operator actually in?** Lessor in own name (→ najem instytucjonalny, 3× deposit cap) or manager acting for owners (→ zarządca under u.g.n.: written contract + OC insurance mandatory)? | Decides the default `legalForm` (A1), deposit caps (B3), and whether C8 applies at all. Single most decisive new question. | law §0, 2.10 |
| E2 | Which legal forms exist in the current portfolio, and who tracks the okazjonalny 14-day registrations + notarial declarations today? | Sizes B1; tells whether A5's clock is already being missed. | law 2.1 |
| E3 | How is the monthly amount quoted to tenants — one number, or split rent / admin / media? Who does the media true-up today and at what cadence? | Validates A2's breakdown shape and F3's settlement design. | comparable #1/#2 |
| E4 | Annexes & extensions (hotspot #2, unchanged): when a tenancy is extended or rent renegotiated, is a new agreement signed or an annex — and how often? | Decides whether D5 (`TenancyExtended`) jumps the queue; also A3's `agreedChange` frequency. | comparable #11 |
| E5 | Deposit practice: what multiplier is actually taken; ever above the statutory cap; how are deductions documented today? | Feeds B3 now and the deposit session (F1). | law 1.3 |
| E6 | Building inspections (new): who orders the art. 62 przeglądy today, where do reports live, is c-KOB in use? | Sizes B2; the "floors 3–5 under construction" property makes this live immediately. | law 2.2 |
| E7 | Leads pool: how long are dead leads kept, is any consent collected at phone capture? | Sets B5's `retainUntil` policy and the info-clause flow. | law 2.7 |
| E8 | Hotspots #1/#9 (responsible person per unit / shared units), #3 (rent day = 10th), #10 (failure data) — **unchanged, still open**; hotspot #12 is resolved by C1. | Continuity of the existing log. | model §7 |

## F. Impacts on the future Accounting session

Constraints to carry in so nothing from this research is lost. (Hotspots #4, #6, #10, #11 all land here.)

| # | Constraint | Detail | Source |
|---|---|---|---|
| F1 | **Deposit lifecycle** (hotspot #4) | Cap per `legalForm` (12×/6×/3×); return within **1 month of `vacateDate`**; **valorization**: refund = multiplier × rent-at-return, never below nominal (so deposit is a *formula*, not an amount — interacts with `RentChangeScheduled`); deductions only against move-in vs move-out `HandoverProtocolRecorded` evidence. | u.o.p.l. art. 6 ust. 1/3/4, 6c (law 1.3/2.5) |
| F2 | **Charge-centric ledger, not invoice-centric** | Atom = charge line on a per-tenancy ledger; invoices/documents are derived views. `RecurringChargeScheduleDefined` + one-off charges; line-item taxonomy `{rent, adminFee, mediaAdvance, deposit, repairRecharge, interest}`; year-in-advance payer = projection, not 12 manual acts; posting date ≠ due date (C6). | Buildium/microrealestate ledger pattern (comparable lessons 1–4) |
| F3 | **One transfer, many components** | Reconciliation by `paymentReference` must *allocate* one payment across charge components (and handle partials) or the green/yellow/red board misreports — the comparable review's biggest structural finding. Media true-up: `MediaSettlementPrepared` → charge or credit, from meter readings (A6). | comparable gaps #2/#7 |
| F4 | **Owner money vs pass-through** | Only czynsz najmu is owner income; admin fee + media are pass-through. Owner statement skeleton: beginning balance → categorized income → categorized expenses (incl. **management fee — the operator's own revenue, currently modeled nowhere**) → disbursement → ending balance. Payout routing per `PropertyOwnershipChanged` % shares (hotspots #10/#11). | DoorLoop/Mzuri (comparable #10) + law 2.6 |
| F5 | **Ryczałt tax cycle** | Per-owner cash-basis received-rent report, payable monthly by the 20th; 8.5%/12.5% with 100 000 zł per-taxpayer limit (rate switch mid-year; spouses share the limit); media borne by tenant excluded from the base — depends on F4's split. Documents: VAT-zw (art. 43 ust. 1 pkt 36), pokwitowanie on demand (KC art. 462). | law 2.6 |
| F6 | **Arrears → legal consequence hook** | Accounting emits `ArrearsReached3Periods` (full payment periods, per art. 11 ust. 2 pkt 2) as the input to the future Arrears Termination Process (D2) — design the arrears projection so "3 full periods" is computable, not just traffic-light colors. | law 2.3 |
| F7 | **`errorAnnulled` reversal semantics** | Accounting owns reversal of charges from phantom tenancies (session decision) — and must keep them out of the ryczałt base, or mistaken invoices look like taxable revenue. | session Phase 3; law 3.6 |

---

*Walkthrough order suggestion: E1 first (it re-scopes A1/B1/B3/C8), then A top-to-bottom, then B, then confirm the D skips, and close by handing F to the Accounting-session agenda.*
