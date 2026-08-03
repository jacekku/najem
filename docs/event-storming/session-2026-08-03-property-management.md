# Event Storming Session — Property Management

**Date:** 2026-08-03
**Participants:** Jacek (domain expert), Claude (DDD facilitator)
**Scope:** Property Management + Tenancies (deep), Accounting at handshake level only

---

## Phase 1: Domain Overview

**Q: What system/domain are we exploring?**
A: Property/rental management with connected finances and accounting, plus (later) lead generation. Polish market. Core value: automate management of finances, units, tenancies; digitalize agreements, permissions, reports. Manager wants at-a-glance payment status per tenant (green = paid, golden = paid a year ahead, yellow = a bit late, red = arrears, bright red = chase arrears).

**Q: Main actors?**
A:
- **Property Manager** — day-to-day operator, near-total control ("expert system")
- **Property Owner** — NOT a system user for MVP; receives reports
- **Tenant** — pays rent with agreed payment reference (tenancy reference in bank transfer title)
- **Guarantor** — e.g. tenant's parents
- **Lead** — parked for now

**Q: External systems?**
A:
- **BNP Paribas** — statements from the owner's bank account (payment reconciliation via transfer title = tenancy reference)
- **Keycloak** — user management
- **S3 / file storage** — documents, photos

**Q: Scope / modules?**
A: Candidate modules: Property Management (properties, units, tenancies), Contacts (leads, tenants, guarantors), User Management (Keycloak, workspaces/building access), Accounting (invoices, bank ingestion, reconciliation, ledgers, arrears), Reporting.

**Decisions:**
- Naming: **Property** = building, **Unit** = rentable thing (flat/room/other). English naming for now, Polish UI localization later.
- Owner is not a user of the system (MVP).
- This session: deep-dive Property Management + Tenancies; Accounting only at the handshake. Accounting is huge — separate session.

---

## Phase 2: Event Discovery

**Q: What happens first — new building onboarding?**
A: Create the property, then create units and assign them to the property. Units don't move between properties. Each unit has a base rent (negotiable per tenancy). Only unit-level change is on/off market availability.

**Q: Who decides a unit goes to market? What makes it unavailable?**
A: The property manager decides. Real case driving the MVP: one property, units on floors 1–2 available, floors 3–5 under construction — listed in the system but not on the market. Unavailability reasons: renovation, cleanup week after move-out, construction. Units move freely between available/unavailable.
- **Open question:** may need a person *responsible for a particular unit* — to confirm with domain expert.

**Q: From interested person to signed tenancy?**
A:
- Photos are taken for the property/unit — need photo storage in the system.
- Viewings: happen in reality, NOT tracked (CRM territory, not MVP).
- Applications: NOT tracked for MVP.
- Unit lifecycle starts at open-to-rent / not-open-to-rent → reserved.
- **Reservation requires an attached contact.**
- Tenancy becomes **active on the agreement start date** (the date from which the tenant may move in / access the unit), not the signature date. Before that: reserved. No intermediate state between reserved and active.
- **Design principle: expert system.** Property manager has near-total control over states, with some checks/confirmations. Do NOT over-constrain state transitions.

**Q: During the tenancy?**
A:
- **Rent changes:** yes — **bitemporal**: decided on day X, effective from day Y (e.g. +2 months).
- Renegotiations: not tracked.
- Annexes / special arrangements: tracked via a free-text **comment/annotation field on the tenancy** (fields may be missing; humans put it there). Annexes are murky — to confirm with domain expert.
- Complaints: not tracked.
- **Repairs: tracked** (minimal: needs-repair / repair-done, no repairmen/supplier management for MVP) because repairs can impact accounting (e.g. no hot water for a week → tenant negotiates a rent-free week).
- Time-based: rent falls due monthly on a settable **rent day**.
- **ASSUMPTION (recorded):** rent day defaults to the 10th of each month (Polish convention). Monthly only — no weekly/biweekly schedules for MVP.

**Q: How does a tenancy end?**
A:
- Natural termination (agreement expiry) or unnatural termination — generic, with **end date + reason comment** (damages, eviction, etc.). No detailed eviction/termination taxonomy for MVP.
- **Deposit: large topic, deferred to another session.**
- After move-out: a **toggle on the end-tenancy action** decides whether the unit goes straight back to market.
- **End-of-tenancy process:** ~a month before the tenancy ends, the manager is prompted: what to do with the unit? Repairs needed? Back to market? Manager decides the unit + tenancy lifecycle.

**Q: Failures and edge cases?**
A:
- Reservation falls through → unit back to open.
- Tenant never signs → back to open.
- **Tenant disappears — PINNED:** rare edge case, manual handling, not in MVP.
- Agreement signed but tenant never moves in — manual handling.
- General rule for MVP: edge cases are escalated to a human.

**Q: Accounting handshake — when does PM tell Accounting something?**
A:
- **TenancyActivated** → expect deposit + rent, generate invoices per the agreement.
- Monthly invoice generation; tenant paying a year in advance → manually generate 12 monthly invoices up-front so there's something to reconcile against.
- **TenancyEnded** (any reason) → tell accounting.
- **Rent or deposit change (bitemporal, future-effective)** → tell accounting so invoices are generated properly.
- Repairs: NOT sent to accounting for now.
- **Architecture decision:** Property Management and Accounting are **separate domains**. A translation layer ("**Tenancy Accounting** control plane") receives PM events (tenancy activated/ended, rent changed) and issues commands to the core Accounting domain ("create charge for contact X, line items Y"). Core accounting stays segregated from PM.

---

## Phase 2 (cont.): Refinements

**Q: Is `UnitReturnedToMarket` a separate event?**
A: No — redundant. `TenancyEnded` carries the back-to-market toggle; the Unit listens and switches itself to open-to-rent. → Modeled as a **policy**: "Whenever TenancyEnded with backToMarket=true, then OpenUnitToRent."

**Q: Track `RentFellDue` monthly?**
A: No — mostly computed, little value as a tracked event. Arrears/arrears-tracing belongs to Accounting (later session).

**Q: One action (create tenancy) or two (reserve, then sign)?**
A: Resolved via a modeling decision: **the reservation lives on the Tenancy, not the Unit.** Desired analysis is a unit timeline (open X days → reserved Y days → tenant lived Z months → ...). The Tenancy carries it: a Tenancy is created in **Reserved** state with tenant(s) attached and a start date; the Unit only gets a reservation flag + link to the not-yet-started tenancy. Tenants connect to the Tenancy (the agreement), never to the Unit. Bitemporal: a new tenancy can be reserved while the old one is still active, so the new tenant starts the day after the old one leaves. Tenancy lifecycle: **Reserved → Active → Ended** (expert system — transitions loosely constrained).

**Q: Multiple tenants per tenancy?**
A: Yes.

**Q: Unit details edited later (photos, description)?**
A: Non-essential changes tracked as a separate non-domain event (catch-all `UnitDetailsUpdated`) on the event stream — not important to the core domain now, extractable later if it becomes relevant (e.g. description A/B testing). (Event-sourcing note: distinguish essential business events from incidental data-change events.)

**Q: Reservation expiring on its own? Tenant swap mid-tenancy?**
A: Both out of MVP. Tenant swap/replacement pinned as post-MVP question.

---

## Consolidated Event List (after refinement)

**Property & Unit setup**
1. `PropertyCreated`
2. `UnitAddedToProperty`
3. `UnitBaseRentSet`
4. `UnitDetailsUpdated` (photos, description — non-domain catch-all)
5. `UnitResponsiblePersonAssigned` *(open question)*

**Unit market state** (expert system, free transitions)
6. `UnitOpenedToRent`
7. `UnitClosedToRent` (with reason: renovation, cleanup, construction)

**Tenancy lifecycle** (Reserved → Active → Ended)
8. `TenancyReserved` (creates tenancy: unit, tenant(s), start date, negotiated rent, rent day; unit gains reservation flag)
9. `TenantAddedToTenancy` / `TenantRemovedFromTenancy` (multiple tenants)
10. `TenancyReservationCancelled` (fell through / never signed → policy: unit back to open)
11. `TenancyActivated` (on agreement start date)
12. `RentChangeScheduled` (bitemporal: decided day X, effective day Y)
13. `TenancyCommentAdded` (annexes, special arrangements)
14. `RepairReported`
15. `RepairCompleted`
16. `TenancyEndingSoon` (~1 month before end — prompts manager decision process)
17. `TenancyEnded` (natural/unnatural, end date + reason, carries backToMarket toggle)

**Policies discovered so far**
- Whenever `TenancyEnded` with backToMarket=true → `OpenUnitToRent`
- Whenever `TenancyReservationCancelled` → unit reservation flag cleared (back to open)
- Whenever `TenancyActivated` / `TenancyEnded` / `RentChangeScheduled` → notify Tenancy Accounting (translation layer → core Accounting commands)

## Phase 3: Timeline & Pivotal Events

Timeline organized as swim lanes (visual: https://claude.ai/code/artifact/c8e9624d-d3d1-4ef8-a515-6e5093372f6b):
- **Lane A** — Property/Unit setup (one-time): PropertyCreated → UnitAddedToProperty → UnitBaseRentSet (+ anytime: UnitDetailsUpdated, UnitResponsiblePersonAssigned?)
- **Lane B** — Unit market cycle (repeats forever): Closed → **Open** → +reserved flag → Occupied → (cleanup) → Open …
- **Lane C** — Tenancy arc (one-shot per tenancy): Reserved → Active → Ended (or ReservationCancelled)

**Pivotal events:** `UnitOpenedToRent` (unit enters the market), `TenancyActivated` (unit occupied, accounting clock starts — closest to point of no return), `TenancyEnded` (accounting clock stops, cycle resets).

**Validation answers:**
1. **Sequence confirmed** — order is right.
2. **Mistaken activation / errors:** errors must be accounted for. On `TenancyActivated`, charges + deposit invoices are generated. For MVP, a mistaken/void tenancy is closed with the same `TenancyEnded` event (which also covers unpaid rent/deposit repercussions) sent to Accounting; Accounting deals with reversal when built. *Facilitator recommendation (accepted direction): give `TenancyEnded` a distinguishable reason type (e.g. `natural` / `terminated` / `error-annulled`) so reporting can exclude phantom tenancies from occupancy stats, and a dedicated `TenancyAnnulled` event can be extracted later if needed.*
3. **Reservation on a closed unit: allowed.** Expert-system freedom — new system, avoid arbitrary constraints; e.g. reserving a future flat on a floor still under construction.
4. **`TenancyEndingSoon`** fires for the natural end date *or* an early-termination date once one is set — either starts the end-of-tenancy flow.

## Phase 4: Enrichment — Cluster 1 (Property & Unit setup)

**Q: Required data for property / unit?**
A:
- **Property:** address, owner. Bank account is NOT attached to property or unit — statements will relate to tenancies/tenants/references. Bank account conceptually belongs to the **owner**, but it's Accounting territory → **parked for the Accounting session**.
- **Unit:** floor, unit number, size = **non-domain "flavor"** (same catch-all treatment as photos/description). **Base rent is domain-specific** — it is an **anchor for negotiation** (price can settle higher or lower), not a minimum.
- Additional unit field discovered: **external listing reference ID** — correlates the unit to listings on external portals (OLX / Otodom), searchable.

## Phase 4: Enrichment — Cluster 2 (Market, leads, reservation, activation)

**Q: What does the manager see before reserving (read model)?**
A: The central screen: a **Unit Board** — list of properties/units, quickly searchable (someone is on the phone). Shows per unit: availability (taken / reserved / available), base rent (anchor, negotiable), amenities, description, floor, photos, external listing reference ID, current/upcoming tenancy. From the unit screen the manager can capture the interested person.

**DISCOVERY — Leads/Applicants pool (reverses earlier "no applications" decision):**
- The person who calls is first captured as a **Lead / Applicant**, not a reservation: name, surname, email/phone, what they're willing to pay, desired start date, unit of interest (manager may offer alternatives).
- Applicants can exist regardless of unit state — even during an active tenancy (e.g. "I'll start in a year").
- Full vetting/CRM process explicitly out of scope for MVP — just the pool.
- **Reservation is a HARD reservation: it happens when the agreement is signed.** So `TenancyReserved` ≈ agreement signed → tenancy exists in Reserved state. (Resolves the earlier signing ambiguity.)

**Q: What's required to reserve?**
A: Unit + at least one contact (name, email, phone) + planned start date (essential). Reservations may be far in the future (e.g. 3 months out while current tenant still in place).
- **THE ONLY hard constraint: two tenancies on the same unit cannot overlap (in time).** Everything else is manager freedom.

**Q: Is `TenancyActivated` automatic or manual?**
A: **DISCOVERY — Pre-activation checklist process** ("tenancy start process"): before activation there are free-form checklist tasks (keys handed over, damage protocol done, pictures taken, …), with priority, prompted to the manager as the start date approaches.
- **Policy:** when the start date arrives — if all checklist items are complete → tenancy **activates automatically**; if not → manager is prompted, tenancy activates **as soon as the checklist completes** (late start).
- **No prorating** for late starts (MVP): normal deposit invoice + normal rent invoice; Accounting is simply told the tenancy started.

**New events from Cluster 1–2 enrichment:**
- `LeadRegistered` (contact info, willing-to-pay, desired start date, unit of interest)
- `LeadConvertedToReservation`(?) — naming TBD
- `ChecklistItemAdded` / `ChecklistItemCompleted` (pre-activation tasks)
- `TenancyStartDateReached` (time trigger feeding the activation policy)

**New invariant:** no overlapping tenancies per unit.

## Phase 4: Enrichment — Cluster 3 (During the tenancy)

**Q: When does Accounting learn about a rent change?**
A: Only when the effective date arrives — concretely, **one day before**: if the scheduled change hasn't been altered by then, the charge for the new rent is created. (Policy: day-before-effective-date check → issue charge command.) The tenancy screen must show **rent history + upcoming changes**.

**DISCOVERY — Timeline read model at every level (key domain-expert wish):**
A CQRS/reporting projection built from multiple event streams (PM + Tenancy Accounting + Accounting):
- **Property level:** occupancy overview — how many units rented / available / unavailable, aggregated across unit & tenancy streams.
- **Unit level:** timeline of tenancies, problems, repairs (open X days → tenant Y months → …).
- **Tenancy level:** the full story — checklist done 5 days before keys handed over, moved in, deposit paid on date A, rent paid on date B, invoice generated but unpaid (shows red). Major events curated (not every event shown).
MVP: build this as a read-side projection from multiple streams.

**Q: Repairs — attached to unit or tenancy?**
A: Repairs can affect the **whole property** (e.g. burst water pipes), a **unit**, or be caused/requested by a **tenant**. Who pays varies (owner; owner-from-deposit between tenancies; tenant invoice) — that's Accounting/Tenancy-Accounting territory, out of PM scope.
**Decision:** the repair lives on the **physical asset — Property or Unit** — optionally connected to/caused by a tenancy. Keep an open mind about other repair targets later. Payment attribution deferred to Accounting session.

## Phase 4: Enrichment — Cluster 4 (Ending)

- **End-of-tenancy checklist: yes** — same checklist mechanism as pre-activation. MVP ships generic items (e.g. "everything paid off ✓"); domain experts will define the real item list after seeing the MVP.
- **1-month warning lead time: fixed** (YAGNI — not configurable for MVP).
- `EndTenancy` command fields confirmed: end date, reason type (natural / terminated / error-annulled), comment, backToMarket toggle.

**Q (user): Should we model compensating events per event now?**
A (facilitator, accepted): No — too much modeling now. Compensating events are only needed where a **downstream side effect must be undone**, i.e. at the money/external boundaries (Tenancy Accounting). `TenancyEnded(reason: error-annulled)` already covers the mistaken-activation case; Accounting owns the reversal. PM-internal corrections are covered by the expert-system freedom (manager can move states freely) plus non-domain catch-all correction events (e.g. `TenancyDetailsCorrected`, like `UnitDetailsUpdated`). Add specific compensators lazily, when a concrete flow demands one.

**New events from Cluster 3–4 enrichment:**
- `RentChargeIssued`-trigger policy (day before effective date) — accounting handshake timing
- `RepairReported` / `RepairCompleted` — scoped `{property | unit}`, optional `causedByTenancy` link
- End-of-tenancy checklist events (same mechanism as pre-activation checklist)
- `TenancyDetailsCorrected` (non-domain catch-all)

**Process managers / sagas identified:**
1. **Tenancy Start Process:** start date approaches → prompt checklist → all complete + date reached → auto `TenancyActivated` → Tenancy Accounting: deposit + rent invoices. Late completion → activate on completion, no prorating.
2. **End-of-Tenancy Process:** 1 month before end date (natural or early-termination) → `TenancyEndingSoon` → prompt end checklist + unit-fate decision → `TenancyEnded` → unit reacts (backToMarket toggle) + Tenancy Accounting notified.
3. **Rent Change Process:** `RentChangeScheduled` → wait until day before effective date → if unchanged, command Tenancy Accounting to create new-rent charge.

## Phase 5: Bounded Contexts & Aggregates

### Bounded Contexts
| Context | Type | Contents |
|---|---|---|
| **Property Management** | Core | Property, Unit, Tenancy, Repair aggregates; checklists; 3 process managers |
| **Contacts** | Supporting | Person registry (tenants, guarantors, leads-as-people) + **unit-interest links** |
| **Tenancy Accounting** | Translation (ACL) | PM events → commands to core Accounting ("create charge for contact X…") |
| **Accounting** | Core (separate session) | Invoices, ledgers, reconciliation, BNP Paribas, arrears |
| **Reporting** | Read-side | Multi-level Timeline projections over PM + Accounting streams |
| **Identity** | Generic | Keycloak, workspaces, building access |

### Aggregates (Property Management)
- **Property** — address, owner ref; minimal invariants; units reference it.
- **Unit** — market state (+reason), base rent anchor, listing ref (OLX/Otodom), **tenancy calendar** (tenancyId, start, end): enforces THE hard invariant — **no overlapping tenancies per unit**. (Could partially serve as read model too.)
- **Tenancy** — Reserved → Active → Ended; tenant/guarantor contact refs; negotiated rent + rent day; bitemporal rent changes; comments; **both checklists live inside Tenancy** (part of its lifecycle, per-tenancy only).
- **Repair** — own aggregate; scope property|unit; optional causedByTenancy; independent lifecycle.

### Decisions
1. **No-overlap invariant enforced in Unit's tenancy calendar** — confirmed.
2. **Checklists inside Tenancy** — confirmed (tenancy lifecycle concern, not unit/property).
3. **Lead = Contact (in Contacts) + interest link to unit(s)** — a contact can be interested in many units. Important connection, but deliberately thin — no CRM overbuild.
4. **Eventual consistency acceptable everywhere** — reservation flag appearing seconds later is fine (there's ample time between lead and signed agreement). No aggregates identified that must update transactionally together.
- Side note from #4: "does one manager own specific units, or do managers share all units?" → ties into pinned question #1 (responsible person per unit).

### Ubiquitous language shifts
- **Reserved** — Unit: derived flag; Tenancy: lifecycle state = agreement signed (hard reservation).
- **Tenant** — Contacts: person; Tenancy: party/role; Accounting: debtor with ledger.
- **Base rent** — negotiation *anchor*, not a floor or minimum.

## Phase 6: Hotspots & Discoveries

**Q1 — Real-world failure points:** The current "system" is multiple Google Sheets tracking who called, what's taken, keys handed, deposit paid. Failures = manual-work errors; no specific failure data yet (domain experts asked to start tracking). The system itself is the remedy. Mistyping/misclicking is the main expected error mode → supports expert-system freedom + correction events + confirmation prompts.

**Q2 — DISCOVERY: Property-level rent target with per-unit leeway.** The owner sets a general **rent target per property**; managers have leeway on unit pricing — one tenancy a bit above anchor, another below — as long as the property-level target is hit. Owner trusts managers to fill units. → New concept: `PropertyRentTargetSet`; future read model: actual rents vs target per property.

**Q3 — Workarounds:** Google Sheets era, new endeavor, few entrenched habits. Good timing.

**Q4 — DISCOVERY: Co-ownership.** One-owner-per-property WILL bite. MVP decision: a property can have **multiple owners with percentage shares**, changeable over time (`PropertyOwnershipChanged`). Payout routing (e.g. married couple 50/50 legally but money goes to one account) is an **Accounting concern** — deferred. Agreement dates confirmed always known at reservation.

**Q5 — Unit restructuring + DISCOVERY: payment reference.**
- Adding units to an operating property: fine, just add. Renumbering: non-essential flavor.
- **Payment reference (bank statement title):** each tenancy carries the agreed transfer title the tenant must use — field on Tenancy (`paymentReference`), not deeply important to PM but **vital for Accounting reconciliation**.
- Split/merge units: **no special flow** (once-in-years frequency) — just `UnitRemovedFromProperty` + create new units.

**New events from Phase 6:**
- `PropertyRentTargetSet`
- `PropertyOwnershipChanged` (owners + % shares, multiple allowed)
- `UnitRemovedFromProperty`
- payment reference field on `TenancyReserved` (or `TenancyPaymentReferenceAssigned`)

## Post-research triage — decisions (answers to synthesis-review.md §E)

**E1 — Operator setup:** The manager is a **zarządca acting on behalf of owners with a written management contract** → C8 applies (store management contract + OC insurance docs). Owner is currently a **physical person**; a **fundacja** will be created soon and building ownership transferred to it → new tenancies switch from najem **zwykły** to **instytucjonalny**. Existing running tenancies must be modeled through the transition.
- Modeling: `legalForm` is **per-tenancy, fixed at signing** — existing zwykły tenancies stay zwykły until re-signed; post-transfer tenancies are instytucjonalny. Ownership transfer = `PropertyOwnershipChanged` (fits the co-ownership model as user noted).
- **NEW DISCOVERY: tenant OC insurance** — tenants are required to hold liability insurance; track per tenancy (policy doc + validity, expiry reminder).

**E2 — Legal forms in portfolio:** zwykły now → instytucjonalny later; **no okazjonalny**. → B1 shrinks: no tax-office registration process, no replacement-premises tracking; what remains is the **instytucjonalny notarial declaration** (`NotarialDeclarationAttached` gating auto-activation). A5's 14-day clock is moot for this portfolio (keep the enum value, skip the process).

**E3 — Rent quoting:** One number to the tenant, includes admin + media, monthly. Who does media true-up: **unknown → ask experts**. → A2 refined: mandatory total, **optional component breakdown** `{rent, adminFee, mediaAdvance}`; encourage split for owner-income/pass-through accuracy; true-up design → Accounting session.

**E4 — Annexes/extensions:** Most likely an annex at end of tenancy (prolong or make indefinite) — **not confirmed, ask experts** (hotspot #2 stays). D5 skip stands; `TenancyExtended` name reserved.

**E5 — Deposit practice:** 1–2 months' rent (Polish standard) — well under statutory caps. Deductions today documented in Google Sheets + photos → covered by `HandoverProtocolRecorded` + `TenancyDocumentAttached`. B3 confirmed as-is.

**E6 — Inspections:** Unknown who orders przeglądy today; **user wants the app to remind managers** → B2 (Property compliance calendar with overdue prompts) confirmed.

**E7 — Leads/PII:** **ARCHITECTURE DECIDED — PII lookaside:** events carry identifiers only, never PII; all PII lives in a separate Postgres table; right-to-be-forgotten = delete the row, event stream untouched. Retention duration for dead leads: **still open → ask experts**.

**Triage acceptance:** A1–A6 accepted (A5 moot for portfolio), B1 (reduced scope), B2–B5 accepted (B5 architecture resolved), C1–C8 accepted (C8 active since zarządca), D1–D9 skips confirmed. Section F carried to Accounting session unchanged.

## Pinned / Open Questions (running list)

| # | Item | Status |
|---|------|--------|
| 1 | Person responsible per unit — needed? | Ask domain expert |
| 2 | Annex-to-agreement handling (beyond comment field) | Ask domain expert |
| 3 | Rent day defaults to 10th monthly | Assumption — verify |
| 4 | Deposit lifecycle | Deferred to separate session |
| 5 | Tenant disappears / never moves in | Manual edge case, not MVP |
| 6 | Accounting internals | Separate session |
| 7 | Tenant swap/replacement mid-tenancy | Post-MVP |
| 8 | Reservation auto-expiry | Not MVP |
| 9 | Do managers own specific units or share all? | Ask domain expert (ties into #1) |
| 10 | Real-world failure data (Google Sheets era) | Domain experts collecting |
| 11 | Owner payout routing for co-owned properties | Accounting session |
| 12 | Rent target vs actual read model shape | Design later, concept captured |
| 13 | Who does media true-up today, at what cadence? | Ask domain expert |
| 14 | Annex vs new agreement for extensions (refines #2) | Ask domain expert |
| 15 | Dead-lead retention duration (PII lookaside decided) | Ask domain expert |
| 16 | Who orders art. 62 przeglądy today; c-KOB in use? | Ask domain expert (B2 built regardless) |
| 17 | Exact timing/legal mechanics of fundacja ownership transfer & tenancy transition | Ask domain expert / lawyer |
