# NAJEM — Property Management Domain Model

**v1.1** — compiled from the Event Storming session of 2026-08-03, amended after the legal + comparable-systems research triage (see §9 Amendments; research in `research/`).
Raw record: `session-2026-08-03-property-management.md`. Visual timeline: https://claude.ai/code/artifact/c8e9624d-d3d1-4ef8-a515-6e5093372f6b

---

## 1. Domain Overview

### Summary
A property management platform for the Polish long-term rental market. Property managers administer buildings (**Properties**) and their rentable **Units**, run **Tenancy** lifecycles from hard reservation (agreement signed) through activation to ending, and hand financial facts to a segregated Accounting domain — invoicing, bank statement ingestion (BNP Paribas), reconciliation via per-tenancy payment references, and arrears tracking. Core value: replace the current multi-Google-Sheets manual process with automation and at-a-glance visibility (who paid / is late / is in arrears), plus clean reports for owners.

**Design philosophy: expert system.** The property manager has near-total control; state transitions are loosely constrained (checks and confirmations, not walls). Exactly **one hard invariant** exists in the whole PM domain: *two tenancies on the same unit cannot overlap in time.* Expected error mode is mistyping/misclicking → corrections via events, not restrictive workflows.

### Actors
- **Property Manager** — day-to-day operator, near-total control; issues virtually every command
- **Property Owner** — NOT a system user (MVP); sets a property-level rent target; receives reports; may be multiple co-owners with % shares
- **Tenant** — party to a tenancy (a tenancy can have several); pays using the agreed payment reference
- **Guarantor** — e.g. a tenant's parents; backs the agreement
- **Lead / Applicant** — a Contact with a registered interest in one or more units

### External Systems
- **BNP Paribas** — statements from the owner's account (Accounting session)
- **Keycloak** — identity/access (Identity context)
- **S3 / file storage** — photos, documents
- **OLX / Otodom** — external listing portals; units carry a listing reference ID for correlation (no integration in MVP, just the field)

---

## 2. Event Flow

### Lane A — Property & Unit Setup (Bounded Context: Property Management)
1. **PropertyCreated** ← Command: CreateProperty by Manager — address, owners (multiple, % shares)
2. **PropertyRentTargetSet** ← owner's target for the whole property; managers price units with leeway around it
3. **PropertyOwnershipChanged** ← co-ownership changes over time (payout routing → Accounting)
4. **UnitAddedToProperty** ← units never move between properties; floor/number/size are non-domain flavor
5. **UnitBaseRentSet** ← the **anchor** for negotiation (settles higher or lower)
6. **UnitDetailsUpdated** ← non-domain catch-all: photos, description, amenities, OLX/Otodom listing ref
7. **UnitRemovedFromProperty** ← rare; also how splits/merges are handled (remove + add new)

---PIVOTAL: **UnitOpenedToRent** (unit enters the market; before this it is inventory)---

### Lane B/C — Market Cycle Meets Tenancy Arc
8. **UnitClosedToRent** / **UnitOpenedToRent** ← Manager, free transitions, with reason (construction, cleanup, renovation)
9. **LeadRegistered** ← contact captured from a phone call: name, contact info, willing-to-pay, desired start date, interest links to unit(s). Can exist regardless of unit state (even mid-tenancy, "I'll start in a year"). No vetting/CRM in MVP.
10. **TenancyReserved** ← **hard reservation = agreement signed.** Unit + tenant contact(s) + start date + negotiated rent + rent day + payment reference. Checked against the Unit's tenancy calendar (THE invariant). Allowed on closed units (e.g. under construction). Read model: Unit Board.
11. **TenantAddedToTenancy / TenantRemovedFromTenancy** ← multiple tenants per tenancy
12. **ChecklistItemAdded / ChecklistItemCompleted** ← pre-activation checklist (keys, damage protocol, photos …)
13. **TenancyReservationCancelled** ← fell through / never signed → Policy: unit reservation flag cleared

---PIVOTAL: **TenancyActivated** (occupied; accounting clock starts — closest point of no return)---
- Fires on the agreement **start date**, via the Tenancy Start Process (see §5): auto if checklist complete, else on checklist completion (late, no prorating)
- Policy: → Tenancy Accounting: create deposit + rent charges per agreement

14. **RentChangeScheduled** ← bitemporal: decided day X, effective day Y. Policy: one day before effective date, if unchanged → command Tenancy Accounting to create the new-rent charge. Tenancy screen shows history + upcoming.
15. **TenancyCommentAdded** ← annexes & special arrangements (free text)
16. **RepairReported / RepairCompleted** ← on Property or Unit, optional causedByTenancy; NOT sent to Accounting (MVP)
17. **TenancyDetailsCorrected** ← non-domain correction catch-all (mistype/misclick era)
18. **TenancyEndingSoon** ← fires 1 month (fixed, YAGNI) before natural end OR early-termination date; starts End-of-Tenancy Process

---PIVOTAL: **TenancyEnded** (accounting clock stops; cycle resets)---
- Fields: end date, reason type (`natural` / `terminated` / `error-annulled`), comment, backToMarket toggle
- `error-annulled` covers mistaken activations; Accounting owns reversal; reporting excludes phantom tenancies from occupancy stats
- Policy: backToMarket=true → **UnitOpenedToRent** (auto); false → Manager closes unit for cleanup/renovation
- Policy: → Tenancy Accounting notified (settlement; unpaid rent/deposit repercussions)

↺ Lane B repeats: open → reserved → occupied → (cleanup) → open …

---

## 3. Bounded Context Canvas: Property Management

**Purpose:** Own the physical portfolio (properties, units) and the tenancy lifecycle; publish financial facts for the accounting side.
**Owner:** core team (MVP)

### Aggregates

#### Property
**Purpose:** Identity and ownership of a building.
**Commands:** CreateProperty {address, owners[{ref, share%}]}, SetPropertyRentTarget {amount}, ChangePropertyOwnership {owners[]}, UpdatePropertyDetails (non-domain)
**Events:** PropertyCreated, PropertyRentTargetSet, PropertyOwnershipChanged, PropertyDetailsUpdated
**Invariants:** ownership shares should total 100% (soft check — confirm, don't block)

#### Unit
**Purpose:** A rentable space: market state + the schedule of tenancy periods.
**Commands:** AddUnitToProperty, SetUnitBaseRent {anchor}, UpdateUnitDetails {photos, description, amenities, listingRef}, OpenUnitToRent, CloseUnitToRent {reason}, RemoveUnitFromProperty, RegisterTenancyPeriod {tenancyId, start, end} *(internal, from reservation flow)*
**Events:** UnitAddedToProperty, UnitBaseRentSet, UnitDetailsUpdated, UnitOpenedToRent, UnitClosedToRent, UnitRemovedFromProperty
**Invariants:**
- **No overlapping tenancy periods** (the only hard rule in the domain) — enforced in the tenancy calendar; RegisterTenancyPeriod rejects overlaps
- Reservation on a closed unit is ALLOWED (expert freedom)

#### Tenancy
**Purpose:** The agreement arc — people, money terms, lifecycle Reserved → Active → Ended.
**Commands:** ReserveTenancy {unitId, contacts[], startDate, negotiatedRent, rentDay, paymentReference}, AddTenant, RemoveTenant, CancelReservation, AddChecklistItem, CompleteChecklistItem, ActivateTenancy *(usually by process)*, ScheduleRentChange {decidedOn, effectiveFrom, amount}, AddComment, EndTenancy {endDate, reasonType, comment, backToMarket}, CorrectDetails (non-domain)
**Events:** TenancyReserved, TenantAdded/Removed, TenancyReservationCancelled, ChecklistItemAdded/Completed, TenancyActivated, RentChangeScheduled, TenancyCommentAdded, TenancyEnded, TenancyDetailsCorrected
**Invariants:** tenancy must have ≥1 tenant contact and a start date; rent day settable (assumed default: 10th); loose transitions otherwise
**Notes:** checklists (pre-activation + end-of-tenancy) live INSIDE Tenancy — they are lifecycle concerns. MVP ships generic items; domain experts will define real lists.

#### Repair
**Purpose:** Track that a physical asset needs/received a repair.
**Commands:** ReportRepair {scope: property|unit, causedByTenancy?}, CompleteRepair
**Events:** RepairReported, RepairCompleted
**Notes:** attaches to the **physical asset**, not the tenancy (repairs outlive tenancies). Who pays = Accounting concern, deliberately out of scope here. Keep open mind for other repair targets later.

### Policies
| Trigger | Action | Type |
|---|---|---|
| TenancyReserved | Register period in Unit calendar; set unit reservation flag (projection) | Auto |
| TenancyReservationCancelled | Clear unit reservation flag | Auto |
| Start date −(some days) | Prompt manager with pre-activation checklist | Auto→Human |
| Start date reached ∧ checklist complete | ActivateTenancy | Auto |
| Start date reached ∧ checklist incomplete | Prompt manager; activate on completion (no prorating) | Auto→Human |
| TenancyActivated | Tenancy Accounting: create deposit + rent charges | Auto |
| RentChange effectiveDate −1 day ∧ unchanged | Tenancy Accounting: create new-rent charge | Auto |
| End date (natural or early-termination) −1 month | TenancyEndingSoon → end checklist + unit-fate prompts | Auto→Human |
| TenancyEnded ∧ backToMarket | OpenUnitToRent | Auto |
| TenancyEnded | Notify Tenancy Accounting (settlement) | Auto |

### Read Models
- **Unit Board** — THE central screen: searchable list of properties/units (someone is on the phone); status, anchor rent, amenities, photos, listing ref, current/upcoming tenancy, reservation flag
- **Timeline** (flagship, CQRS over multiple streams):
  - Property level: occupancy — units rented / open / closed, (future) actual rents vs owner's target
  - Unit level: history — open X days → reserved → tenant Y months → repairs, problems
  - Tenancy level: the story — checklist done, keys handed, deposit paid, rent paid / invoice generated-but-unpaid (red), curated major events incl. Accounting stream
- **Starting-soon / Ending-soon lists** — tenancies needing checklist attention

### Inbound
- Contact identities from **Contacts** (by reference)
- Payment/settlement facts from **Accounting** (for Timeline read model only)

### Outbound
- TenancyActivated, TenancyEnded, RentChangeScheduled(+day-before trigger) → **Tenancy Accounting**
- All events → **Reporting** projections

---

## 4. Other Contexts (thin canvases)

### Contacts (Supporting)
Person registry: tenants, guarantors, leads-as-people. Fields per contact: name, surname, email, phone. **Interest links**: contact → unit(s) they're interested in, + willing-to-pay, desired start date. Deliberately thin — no CRM/vetting in MVP. Events: ContactRegistered, InterestRegistered (naming TBD).

### Tenancy Accounting (Translation layer / ACL)
Receives PM events; issues commands to core Accounting ("create charge for contact X, line items Y"). Protects core Accounting from the PM model and vice versa. Owns the mapping tenancy → charges (deposit, monthly rent, year-in-advance manual generation). Detail: Accounting session.

### Accounting (Core — separate session)
Charges, invoices, tenant ledgers, BNP Paribas statement ingestion, reconciliation by payment reference, arrears statuses (green/golden/yellow/red/bright-red), owner payout routing for co-owners.

### Reporting (Read-side)
Pure projections (the Timeline family + payment status boards). No commands, no aggregates. Consumes PM + Accounting streams.

### Identity (Generic)
Keycloak; users, workspaces, per-building access. Technical.

---

## 5. Process Managers

### Tenancy Start Process
**Trigger:** TenancyReserved (arms on start date)
| Step | What happens |
|---|---|
| 1 | Start date approaching → prompt manager with pre-activation checklist (keys, damage protocol, photos…) |
| 2 | Start date reached + checklist complete → ActivateTenancy (auto) |
| 2' | Checklist incomplete → keep prompting; ActivateTenancy fires when checklist completes (late; NO prorating) |
| 3 | TenancyActivated → Tenancy Accounting: deposit + rent charges per agreement |
**Completion:** TenancyActivated + charges created
**Failure mode:** reservation cancelled before start → process disarmed; edge cases (tenant vanishes) → human

### End-of-Tenancy Process
**Trigger:** end date (natural or early-termination) −1 month → TenancyEndingSoon
| Step | What happens |
|---|---|
| 1 | Prompt manager: end checklist (MVP generic, e.g. "everything paid off ✓"), repairs needed?, unit fate |
| 2 | Manager issues EndTenancy {endDate, reasonType, comment, backToMarket} |
| 3 | TenancyEnded → Tenancy Accounting notified (settlement, unpaid rent/deposit) |
| 4 | backToMarket=true → UnitOpenedToRent (policy); false → manager closes for cleanup/renovation |
**Completion:** TenancyEnded processed, unit fate applied

### Rent Change Process
**Trigger:** RentChangeScheduled {decidedOn, effectiveFrom, amount}
| Step | What happens |
|---|---|
| 1 | Wait until effectiveFrom −1 day |
| 2 | If the change still stands → command Tenancy Accounting: create charge at new rent |
**Completion:** new-rent charge exists before the effective month
**Failure mode:** change edited/cancelled before the day → nothing sent

---

## 6. Key Scenario: From phone call to moved-in tenant

1. **Manager** answers a call; opens the **Unit Board**, searches by the OLX/Otodom listing reference the caller mentions
2. Sees unit 12 is open, anchor rent 2600 PLN, photos, amenities; the caller wants it from Oct 1 but would pay 2400
3. Manager registers a **Lead**: Anna Kowalska, phone, willing-to-pay 2400, desired start Oct 1, interested in unit 12 (and unit 14 as alternative)
4. A week later the agreement is signed at 2500 → Manager issues **ReserveTenancy** (unit 12, Anna + guarantor contact, start Oct 1, rent 2500, rent day 10th, payment reference "NAJEM/12/2026/A-KOW")
5. The **Unit** aggregate checks its tenancy calendar — no overlap → period registered, unit shows reserved
6. As Oct 1 approaches, the **Tenancy Start Process** prompts the checklist: keys ✓, damage protocol ✓, photos ✓
7. Oct 1 arrives, checklist complete → **TenancyActivated** automatically → **Tenancy Accounting** creates the deposit charge and October's rent charge
8. Anna pays with the agreed reference; Accounting reconciles (separate session); the **Timeline** shows her tenancy green
9. In March the owner wants more → Manager schedules a rent change: decided Mar 15, effective Jun 1, 2600. On May 31 it still stands → new-rent charge issued
10. Aug 1 (a month before the Sep 1 end date): **TenancyEndingSoon** → end checklist prompted, "everything paid off ✓", backToMarket=true
11. Sep 1: **TenancyEnded (natural)** → Accounting settles; unit auto-reopens on the market; the cycle repeats

**Alternative:** Anna never signs → reservation cancelled → unit flag cleared, back to open. Manager fat-fingers a wrong tenancy into existence → **TenancyEnded (error-annulled)** → reporting excludes it, Accounting reverses.

---

## 7. Hotspot Log

| # | Location | Description | Status | Decision |
|---|---|---|---|---|
| 1 | Unit | Responsible person per unit — needed? Do managers own units or share all? | Open | Ask domain expert |
| 2 | Tenancy | Annex handling beyond the comment field | Open | Ask domain expert |
| 3 | Tenancy | Rent day defaults to 10th, monthly only | Assumption | Verify |
| 4 | Tenancy/Accounting | Deposit lifecycle (large) | Deferred | Separate session |
| 5 | Tenancy | Tenant disappears / never moves in | Parked | Manual, not MVP |
| 6 | Accounting | Full accounting internals | Deferred | Separate session |
| 7 | Tenancy | Tenant swap/replacement mid-tenancy | Parked | Post-MVP |
| 8 | Tenancy | Reservation auto-expiry | Parked | Not MVP |
| 9 | PM | Real-world failure data (Google Sheets era) | Open | Experts collecting |
| 10 | Property/Accounting | Owner payout routing for co-owners | Deferred | Accounting session |
| 11 | Reporting | Rent target vs actual read model shape | Open | Concept captured, design later |
| 12 | Tenancy | Implied event: early-termination date being set (feeds TenancyEndingSoon) | **Resolved** | `TerminationNoticeGiven` (§9) |
| 13 | Accounting | Who does media true-up today, at what cadence? | Open | Ask domain expert |
| 14 | Tenancy | Annex vs new agreement for extensions (refines #2) | Open | Ask domain expert |
| 15 | Contacts | Dead-lead retention duration (PII lookaside decided) | Open | Ask domain expert |
| 16 | Property | Who orders art. 62 przeglądy today; c-KOB in use? | Open | Ask expert; B2 built regardless |
| 17 | Property/Tenancy | Fundacja ownership transfer timing & tenancy transition mechanics | Open | Ask domain expert / lawyer |

---

## 8. Ubiquitous Language

| Term | Context | Definition |
|---|---|---|
| Property | PM | The building; has address, co-owners (% shares), rent target |
| Unit | PM | The rentable thing (flat/room/other); has anchor rent, market state, tenancy calendar |
| Base rent | PM | The **anchor** for negotiation — not a minimum, not a cap |
| Rent target | PM | Owner's target for the whole property; managers have per-unit leeway |
| Reserved | Unit (PM) | Derived flag: an upcoming tenancy exists |
| Reserved | Tenancy (PM) | Lifecycle state: **agreement signed** — a hard reservation |
| Lead / Interest | Contacts | A contact + link(s) to unit(s) of interest, willing-to-pay, desired start |
| Tenant | Contacts | A person |
| Tenant | Tenancy (PM) | A party to the agreement (≥1 per tenancy) |
| Tenant | Accounting | A debtor with a ledger |
| Guarantor | Contacts/Tenancy | Person backing the agreement |
| Payment reference | Tenancy/Accounting | Agreed bank-transfer title; reconciliation key |
| Checklist | Tenancy | Pre-activation / end-of-tenancy task list inside the tenancy |
| backToMarket | Tenancy→Unit | Toggle on EndTenancy deciding the unit's fate |
| Expert system | All | Design stance: manager freedom, soft checks, one hard invariant |

## Context Map

| Upstream | Downstream | Pattern | Notes |
|---|---|---|---|
| Property Management | Tenancy Accounting | OHS → ACL | PM publishes domain events; TA translates to Accounting commands. **Compliance seat:** the ACL warns-and-confirms (never silently auto-issues) on charges violating statutory rules — deposit over cap, unilateral rent increase breaching art. 8a/9 |
| Tenancy Accounting | Accounting (core) | Customer–Supplier | TA is the only writer of tenancy-driven charges |
| Contacts | Property Management | Customer–Supplier | PM references contact IDs |
| PM + Accounting | Reporting | Conformist | Pure projections over both streams |
| Identity (Keycloak) | all | OHS | Generic subdomain |

---

## 9. Amendments (v1.1) — from the research triage

Accepted from `research/synthesis-review.md` after the product-owner walkthrough (2026-08-03). Section F of that document is the standing constraint list for the Accounting session.

### Operator & legal context (triage E1/E2)
- The manager is a **zarządca acting for owners under a written management contract** → store management contract + manager's OC insurance docs per owner relationship (C8).
- Owner today: physical person, najem **zwykły**. A **fundacja** will soon take ownership (via `PropertyOwnershipChanged`) → new tenancies become najem **instytucjonalny**. `legalForm` is **per-tenancy, fixed at signing**: running zwykły tenancies stay zwykły until re-signed. No okazjonalny in this portfolio (enum value kept; its processes — 14-day tax registration, replacement premises — not built).
- **Tenant OC (liability) insurance is required** → tracked per tenancy: policy document + validity dates + expiry reminder.

### Tenancy aggregate — amended fields & events
- `TenancyReserved` now carries: `legalForm {zwykly | okazjonalny | instytucjonalny}`, `term {fixedTerm(endDate) | indefinite}`, **monthly total (mandatory) + optional breakdown `{rent, adminFee, mediaAdvance}`** (tenant is quoted one number; split serves owner-income vs pass-through and ryczałt — true-up design in Accounting session), `depositMultiplier/depositAmount` (practice: 1–2× rent; ACL warns vs statutory cap per legalForm).
- `RentChangeScheduled` gains `changeType {agreedChange | unilateralIncrease | indexation}`; ACL warn-and-confirm on unlawful `unilateralIncrease` (art. 8a/9: 3-month notice, 6-month frequency). Reserved: `RentIncreaseRefusedByTenant`.
- `TenancyEnded.reasonType` widened: `{agreementExpiry | mutualAgreement | tenantNotice | landlordNotice | rentIncreaseRefusal | vacateDemand | courtEviction | errorAnnulled}` + new `vacateDate` (starts the deposit clock).
- New events: `TerminationNoticeGiven {ground, noticeDate, effectiveDate, noticeDoc}` (resolves hotspot #12; feeds `TenancyEndingSoon`), `HandoverProtocolRecorded {type: moveIn|moveOut, meterReadings[], conditionNotes, photos, signedDoc, date}` (replaces free-form damage-protocol checklist item; legal basis of settlement — art. 6c), `TenancyDocumentAttached {docType: agreement | annex | notice | guarantorSurety | insurancePolicy | other, s3Ref, validity?, date}`, `NotarialDeclarationAttached` (instytucjonalny; gates **auto**-activation — manual activation stays possible, warned).
- Indexation clause metadata `{indexType, anniversaryMonth, cap}` + yearly policy proposing `RentChangeScheduled(indexation)` to the manager. Warn on term > 10 years at reservation.
- `RepairReported` gains `statutoryDutyHint {landlord | tenant | negotiable}` (art. 6a/6b).

### Property aggregate — compliance calendar (B2)
- `InspectionCompleted {propertyId, type: gas|chimney|electrical-5yr|annual|smokeCO, date, reportDoc, findings}` + next-due computation + **overdue prompts** (same mechanism as `TenancyEndingSoon`). Feeds the Property Timeline "problems" lane and the new **statutory-deadlines Timeline lane** (with B1/B3/C1 deadline events).

### End-of-Tenancy Process — amended
- Emits `DepositSettlementDue {tenancyId, deadline = vacateDate + 1 month}` (the one deadline with litigation risk; full deposit lifecycle remains deferred — hotspot #4).
- Move-out `HandoverProtocolRecorded` becomes a typed step (meter readings → media true-up input for Accounting).

### Contacts — PII architecture (B5, DECIDED)
- **PII lookaside:** events carry identifiers only, never PII; all PII in a separate Postgres table; right-to-be-forgotten = row deletion, event stream untouched. `LeadRegistered` gains `{lawfulBasis, infoClauseServedAt, retainUntil}`; stale-lead prompt/erasure policy; retention duration → hotspot #15.

### Tenancy Accounting — charge command amendments
- Charges carry **line items per component** `{rent, adminFee, mediaAdvance, deposit, …}` and **posting date + due date** explicitly.

### Confirmed MVP skips (D1–D9)
Proration (first fast-follow), full arrears-termination workflow (after Accounting; trigger `ArrearsReached3Periods`), full rent-increase workflow, configurable ending-soon lead time, renewals (`TenancyExtended` reserved), late fees, tenant portal/e-sign/screening/CRM/vendors/syndication, vacancy KPI projections, CEEB.
