# Comparable Systems Review — Property/Rental Management Domain

**Date:** 2026-08-03
**Purpose:** Validate the event-storming model (`../session-2026-08-03-property-management.md`) against established international platforms (Buildium, AppFolio, DoorLoop, TenantCloud, Landlord Studio), Polish-market tools and services (Rentumi, simpl.rent, Mzuri, Rentio, RentSoft), open-source codebases, and DDD practice.
**Output:** modeling lessons, missing domain concepts, and a ranked gap table against the MVP (Polish long-term rental, bank-statement reconciliation, owner reports).

---

## 1. Systems reviewed (quick profiles)

### International platforms

| System | What their model teaches |
|---|---|
| **Buildium** | The **[lease ledger](https://www.buildium.com/dictionary/lease-ledger/)** is the financial record of a single tenancy — every charge, payment, credit from move-in to move-out. **[Recurring charge templates](https://www.buildium.com/blog/managing-recurring/)** (rent, pet fee, parking) post automatically; a **["posting day"](https://support.buildium.com/hc/en-us/articles/200833816-How-do-I-change-when-recurring-charges-post-)** setting posts a charge dated the 1st several days early. [Owner statements pull from the same ledger data](https://www.buildium.com/blog/property-management-billing-software-automation/). [KPIs tracked](https://www.buildium.com/blog/property-management-kpis-to-track/): occupancy/vacancy rate, rent collection rate, turnover, average days-to-lease, repair cost. |
| **AppFolio** | Full leasing funnel (screening, applications, e-sign, renewals, move-out services), [late-fee calculator, recurring charges](https://www.doorloop.com/blog/doorloop-vs-appfolio); heavier CRM than we need. |
| **DoorLoop** | [Owner-statement anatomy](https://www.doorloop.com/hub/owner-statements): beginning balance → categorized income (rent, late fees, other) → categorized expenses (repairs, mgmt fee) → owner disbursement (amount, method, date) → ending balance / reserve target. [Disbursement management](https://www.doorloop.com/blog/how-to-manage-owner-disbursements) is its own workflow. |
| **TenantCloud / Landlord Studio** | Small-landlord tier: [automated invoicing, receipts, late-fee calculation, rent reminders, tenant portal, maintenance requests](https://www.tenantcloud.com/tenant); [recurring expenses + email/SMS reminder templates](https://www.landlordstudio.com/blog/landlord-studio-vs-tenant-cloud). Shows the floor of what even solo landlords expect. |

### Polish market

| System / service | What a Polish manager expects |
|---|---|
| **Rentumi** ([app](https://aplikacja.rentumi.pl/artykuly/rozliczanie-mediow-w-wynajmowanym-mieszkaniu/)) | **Meter readings per unit are a first-class feature** ("control meter readings in rental properties"), payment-delay tracking ("who owes money, does the tenant pay regularly" — exactly our green/yellow/red wish). |
| **simpl.rent** ([platform](https://simpl.rent/)) | [Tenant verification](https://simpl.rent/profesjonalista/weryfikacja-najemcy/) (identity, income vs rent, credit/payment history), Tenant Certificate, **electronic signing of rental agreements**, rent-payment insurance tied to najem okazjonalny. |
| **Mzuri** ([service](https://mzuri.pl/zarzadzanie-najmem/), ~7,000 units) | The operating benchmark for our manager persona: monthly owner report (received from tenant vs expenses), **weekly vacancy (pustostan) reports** during void periods (inquiries, viewings, feedback), **settles utilities and administration (wspólnota) on the owner's behalf**, runs collection procedures (windykacja) on late payment. |
| **Rentio / RentSoft / easyRenti / RentEasy.AI** ([overview](https://www.rsgn.pl/aplikacje-do-zarzadzania-najmem-ktore-warto-znac)) | Common denominator: rent-payment tracking, **meter-consumption monitoring**, contract generation, payment reminders, owner reports, windykacja modules. |

### Polish domain rules (not tools, but every tool encodes them)

- **Three-way payment split** — [czynsz najmu vs czynsz administracyjny vs media](https://www.rentstandard.pl/blog/czynsz-a-oplaty-i-media) ([Rendin guide](https://rendin.pl/articles/jak-rozliczac-czynsz-i-oplaty-administracyjne-w-najmie)): rent is the owner's income; administrative fee (to the wspólnota/spółdzielnia) and utilities are **pass-through** money. Settlement methods for media ([mieszkanicznik](https://mieszkanicznik.org.pl/jak-rozliczac-media-z-najemcami-zeby-nie-tracic-pieniedzy-i-nie-tworzyc-konfliktow/), [Otodom](https://www.otodom.pl/wiadomosci/rozliczanie-mediow-w-umowie-najmu/)): flat ryczałt, or **monthly zaliczki (advances) with periodic true-up against actual bills/meter readings** — the true-up is the classic manager pain point.
- **Waloryzacja / indexation** ([kluczo.pl](https://kluczo.pl/blog/indeksacja-czynszu), [waloryzator.pl](https://waloryzator.pl/baza-wiedzy/waloryzacja-w-umowach-najmu-instytucjonalnego)): annual rent indexation by the GUS CPI index (published in Monitor Polski for the prior year); contract clause driven; for najem instytucjonalny parties set rules freely (statutory 15%/yr cap for some post-2019 contracts).
- **Najem okazjonalny** ([kluczo.pl guide](https://kluczo.pl/blog/umowa-okazjonalna)): mandatory attachments — **protokół zdawczo-odbiorczy incl. meter readings + photos**, notarial submission-to-execution declaration, substitute-premises declarations; **deposit capped at 6× monthly rent**; agreement must be **registered with the tax office within 14 days** of tenancy start.

### Open source / DDD

- **[microrealestate](https://github.com/microrealestate/microrealestate)** (MIT, actively maintained): landlords → properties → tenants → **leases with customizable templates** → rent-payment tracking → custom documents; separate landlord and tenant frontends. Its rent model: per-lease rent records per period with payment status — i.e., a lease ledger again.
- GitHub topic sweeps ([property-management](https://github.com/topics/property-management), [rental-management](https://github.com/topics/rental-management)) show the recurring OSS shape: rooms/units + contracts + **utility bills** + payments + deposits (e.g. Laravel boarding-house systems modeling "rooms, tenants, contracts, utility bills, payments"). Utility billing appears in nearly every one; none model reservation-before-lease as richly as we do.
- DDD literature ([bounded-context guidance](https://milanjovanovic.tech/blog/bounded-context-ddd-explained), [Fowler](https://martinfowler.com/bliki/BoundedContext.html)) uniformly treats **billing as its own bounded context** with its own model of the shared entity — directly validating our PM / Tenancy Accounting (ACL) / Accounting split. No off-the-shelf "lease aggregate" sample surfaced that beats our session's model; the closest analogue is the ubiquitous ledger-per-lease pattern.

---

## 2. Modeling lessons — validated vs challenged

### Validated by the field

1. **Tenants attach to the lease, never the unit.** Every system (Buildium lease ledger, microrealestate lease, AppFolio) hangs tenants, charges, and documents on the lease/tenancy. Our decision that *"tenants connect to the Tenancy, never the Unit"* and **reservation-on-Tenancy** (`TenancyReserved` creates the aggregate) is the industry shape. Nobody models reservation as a unit state; a lease with a future start date *is* the reservation — exactly our model.
2. **Unit tenancy calendar + no-overlap invariant.** Implicit in every system (you can't double-book a unit's lease dates); our explicit calendar in the Unit aggregate is a clean formalization and doubles as the unit timeline read model, as noted in the session.
3. **Translation layer to Accounting.** Buildium/DoorLoop internally do the same: leasing events *post charges to a ledger*; the ledger, not the lease, is the accounting truth. DDD sources confirm billing-as-separate-context. Our **Tenancy Accounting ACL emitting "create charge" commands** matches both.
4. **Day-before charge issuance.** Buildium's *posting day* (post the rent charge N days before its due date) is the productized version of our Rent Change Process policy ("day before effective date → issue charge"). Lesson: make "when the charge is posted" vs "when it is due" **two explicit dates** in Tenancy Accounting.
5. **Checklists ≈ move-in/move-out workflows.** AppFolio's move-in/move-out services and the Polish protokół zdawczo-odbiorczy legally anchor our pre-activation and end-of-tenancy checklists (`ChecklistItemAdded/Completed`). Good call putting them inside Tenancy.
6. **Expert-system freedom.** Mzuri's human-operated model (managers decide everything, system records) supports loose state transitions + correction events over hard workflow enforcement.
7. **Amenities/floor/size as non-domain flavor** — correct; no system treats amenities as behavior-bearing domain state, only as listing/search attributes.
8. **`PropertyRentTargetSet` is a differentiator** — no comparable per-property target-vs-actual concept found in any reviewed product. Keep it.

### Challenged / refine

1. **The lease ledger is charge-centric, not invoice-centric.** Everywhere, the atom is the **charge line** (recurring template or one-off) on a per-tenancy ledger; "invoice" is presentation. Our session speaks of "generate invoices per the agreement." Recommendation: in Tenancy Accounting, model **RecurringChargeScheduleDefined** (rent, utility advance, admin-fee pass-through — typed components) + **one-off charges** (repair recharge, late fee), and let invoices/documents be derived. This also makes the year-ahead-payer (12 invoices up-front) a projection concern, not 12 manual acts.
2. **One monthly payment, many components.** The Polish three-way split means the tenant's single transfer (matched by our `paymentReference`) covers rent + zaliczka na media + sometimes admin fee. Reconciliation must **allocate one payment across charge components**, or arrears status (green/red) misreports. This is the biggest structural finding for the Accounting session.
3. **Renewal/extension is a real lifecycle step.** Buildium/AppFolio model lease renewal explicitly (and track renewal rate as a KPI). Our model ends a tenancy and starts a new one, or uses the comment field (pinned question #2, annexes). Acceptable for MVP, but expect `TenancyExtended` / `TenancyRenewed` to be demanded quickly — it preserves ledger continuity and deposit carry-over, which "end + new" breaks.
4. **`TenancyEndingSoon` at fixed 1 month** conflicts with Polish notice periods (often 1–3 months) and renewal decisions typically made earlier; fine for MVP (YAGNI accepted), just noting the field norm is configurable lead time.
5. **No prorating (MVP)** is a deliberate divergence — every international system prorates the first/last month. In Polish practice mid-month starts are common and typically prorated per diem. The manager can hand-edit the first charge, so it's survivable, but the "no prorating" policy will generate manual corrections from day one.

---

## 3. Domain concepts we're missing

Compared against our consolidated event list and aggregates:

1. **Media / utilities settlement + meter readings** — in *every* Polish tool and most OSS projects; entirely absent from our model. Needs: meter set per unit (typed: prąd/gaz/woda/ciepło), `MeterReadingRecorded` (at minimum at move-in/move-out — feeds the protokół), utility-advance (zaliczka) as a recurring charge component, and a **periodic true-up settlement** (`MediaSettlementPrepared` → charge or credit) in Tenancy Accounting.
2. **Charge composition: czynsz najmu vs czynsz administracyjny vs media** — the tenancy's "negotiated rent" is really a **typed money breakdown**. Owner reports depend on it: admin fee and media are pass-through, only rent is owner income (Mzuri's monthly report makes exactly this distinction).
3. **Rent indexation (waloryzacja)** — annual GUS-CPI adjustment clause. Good news: `RentChangeScheduled` (bitemporal) is the correct primitive; missing is only the **clause metadata on Tenancy** (index type, anniversary month, cap) + a yearly policy that proposes the change to the manager.
4. **Agreement documents on the Tenancy** — scan/PDF of the signed agreement and the najem okazjonalny attachment set (notarial declaration, substitute-premises declarations, protokół). We have S3 and photos-on-unit but no `TenancyDocumentAttached`. E-sign (simpl.rent-style) is separate and later.
5. **Tenancy legal type** — zwykły / okazjonalny / instytucjonalny changes deposit cap (6× rent okazjonalny), required attachments, and the 14-day tax-office registration duty (a natural pre-activation checklist item). One enum field + checklist templates covers MVP.
6. **Late fees / statutory interest (odsetki ustawowe)** — universal internationally; in Poland usually statutory interest on arrears rather than flat fees. Accounting-session concern; ensure the charge model admits interest lines.
7. **Prorated first/last month** — see challenge #5 above.
8. **Tenant notifications / reminders** — automated rent reminders are credited with materially reducing late payments in every small-landlord tool. Manager-mediated comms (Mzuri model) is fine for MVP; a reminder policy off the arrears projection is a cheap later win. Full tenant portal: not MVP.
9. **Vacancy KPIs** — occupancy rate, days-to-lease, turnover, rent collection rate (Buildium KPI set); Mzuri's weekly pustostan report. Our Timeline read model already contains the raw events; this is projection work, not new domain events. `TenancyEnded(reason)` typing (accepted in session) is what keeps these stats honest.
10. **Owner statement structure** — beginning balance / income by category / expenses (incl. management fee!) / disbursement / ending balance. Our own **management fee** is not modeled anywhere yet — the manager's revenue! Flag for the Accounting session alongside owner payout routing (pinned #11).
11. **Maintenance beyond repair-done** — vendors, work orders, tenant-submitted requests. Deliberately out (correct for MVP); our Repair aggregate placement (asset-scoped, optional causedByTenancy) matches how field systems attribute work orders.
12. **Tenant screening / verification** — simpl.rent territory; integration candidate later, never core domain.

---

## 4. Ranked gap table

**Bite = when it hurts the MVP** (Polish long-term rental, BNP statement reconciliation, owner reports).

| # | Gap | Where it lands in our model | Bites | Rationale |
|---|---|---|---|---|
| 1 | **Charge composition** (rent / czynsz admin. / media advance as typed components) | Tenancy (`TenancyReserved` payload), Tenancy Accounting charge commands | **Now** | One bank transfer covers all components; reconciliation + green/red status + owner income-vs-pass-through all misreport without it. |
| 2 | **Utility advances + periodic media true-up** | New events in Tenancy Accounting (`MediaSettlementPrepared`, settlement charge/credit) | **Now** (design in Accounting session) | The #1 Polish manager task after rent collection; every PL tool has it. Design the charge model so true-ups fit even if UI ships post-MVP. |
| 3 | **Meter readings** (`MeterReadingRecorded`, move-in/move-out minimum) | Unit (meter set) + Tenancy checklists (protokół items) | **Now** (capture), Later (billing from readings) | Legally part of protokół zdawczo-odbiorczy; cheap to capture as structured checklist data, expensive to retrofit. |
| 4 | **Tenancy documents** (`TenancyDocumentAttached`: agreement scan, okazjonalny attachments) | Tenancy + S3 | **Now** | "Digitalize agreements" is stated core value; storage already in scope; trivial event. |
| 5 | **Tenancy legal type** (zwykły/okazjonalny/instytucjonalny) + type-driven checklist templates (14-day US registration, notarial declaration) | Tenancy field + checklist seeding | **Now** | One enum; drives deposit cap and compliance checklist. Retrofitting classification onto live tenancies is painful. |
| 6 | **Owner statement + management fee** | Accounting/Reporting sessions | **Now** (next session's agenda) | "Owner reports" is an MVP pillar; the statement skeleton (balance→income→expenses→disbursement) and our own fee must be first-class there. |
| 7 | **Payment allocation across components** | Accounting (reconciliation) | **Now** (Accounting session) | Partial payments / one transfer, many charges — the arrears traffic-light depends on allocation rules. |
| 8 | **Prorated first/last month** | Tenancy Start Process / Tenancy Accounting | **Later** (soon) | Session decided against; manual charge edit is the workaround; expect it in the first post-MVP batch. |
| 9 | **Indexation clause (waloryzacja)** | Tenancy metadata + yearly policy proposing `RentChangeScheduled` | **Later** | Annual cadence; manager can do it by hand via existing `RentChangeScheduled` in year one. |
| 10 | **Late fees / statutory interest** | Accounting | **Later** | Arrears *visibility* is MVP; arrears *monetization* is not. Keep charge model open to interest lines. |
| 11 | **Renewal/extension (`TenancyExtended`)** | Tenancy lifecycle | **Later** | End+new works but breaks ledger/deposit continuity; ties to pinned #2 (annexes). |
| 12 | **Rent reminders / tenant notifications** | Policy on arrears projection | **Later** | Proven arrears reducer; needs Accounting projections first. |
| 13 | **Vacancy KPIs** (days-to-lease, occupancy %, collection rate) | Reporting projections (Timeline) | **Later** | Raw events already captured; pure read-side work. Mzuri-style weekly pustostan digest is a nice differentiator. |
| 14 | **Tenant portal / e-sign** | New context / integration (simpl.rent, Autenti) | **Later / post-MVP** | Owner isn't a user; tenant needn't be either for MVP. |
| 15 | **Tenant screening/verification** | Integration (simpl.rent) | **Never (core)** | Buy, don't build. |
| 16 | **Applications/viewings CRM, listing syndication** | Beyond lead pool + external listing ref | **Never (MVP)** | Session decision holds; external listing reference ID is the right minimal hook. |
| 17 | **Amenities taxonomy** | Unit flavor | **Never** | Field consensus: listing attribute, not domain state. |
| 18 | **Vendor/work-order management** | Repair aggregate extension | **Never (MVP)** | Minimal repair tracking matches session scope. |

**Deposit lifecycle (pinned #4)** — reinforced by research: cap rules per legal type (6× okazjonalny), 1-month return deadline after vacating, deductions vs protokół evidence. The deferred session should consume gaps #3 and #5 as inputs.

---

## 5. Bottom line

Our aggregate shape (Tenancy-carries-reservation, Unit tenancy calendar, Accounting behind an ACL, checklists in Tenancy) is **validated by every mature system reviewed** — no structural rework indicated. The model's blind spot is uniformly Polish-market money mechanics: **the tenant's payment is not "rent", it is a composite of rent + pass-through components**, and utilities settlement with meter readings is table stakes in every Polish tool. Fix the charge-composition assumption before the Accounting session; capture meter readings and documents now while they're cheap; defer indexation, proration, renewals, reminders, and KPIs as fast-follows.

---

## Sources

- Buildium: [Lease ledger](https://www.buildium.com/dictionary/lease-ledger/) · [Tenant ledger](https://www.buildium.com/dictionary/tenant-ledger/) · [Recurring transactions](https://www.buildium.com/blog/managing-recurring/) · [Posting day](https://support.buildium.com/hc/en-us/articles/200833816-How-do-I-change-when-recurring-charges-post-) · [Billing automation](https://www.buildium.com/blog/property-management-billing-software-automation/) · [11 PM KPIs](https://www.buildium.com/blog/property-management-kpis-to-track/) · [Accounting basics (APM Help)](https://www.apmhelp.com/blog/buildium/property-management-accounting-basics-buildium)
- DoorLoop: [Owner statements](https://www.doorloop.com/hub/owner-statements) · [Owner disbursements](https://www.doorloop.com/blog/how-to-manage-owner-disbursements) · [DoorLoop vs AppFolio](https://www.doorloop.com/blog/doorloop-vs-appfolio) · [Rentec: PM report contents](https://www.rentecdirect.com/blog/property-management-report/)
- TenantCloud / Landlord Studio: [Tenant portal](https://www.tenantcloud.com/tenant) · [Rent collection](https://www.tenantcloud.com/rent-collection) · [LS vs TC](https://www.landlordstudio.com/blog/landlord-studio-vs-tenant-cloud) · [Stessa comparison](https://www.stessa.com/blog/landlord-studio-vs-tenantcloud/)
- Polish tools/services: [Rentumi — rozliczanie mediów](https://aplikacja.rentumi.pl/artykuly/rozliczanie-mediow-w-wynajmowanym-mieszkaniu/) · [simpl.rent](https://simpl.rent/) · [simpl.rent verification](https://simpl.rent/profesjonalista/weryfikacja-najemcy/) · [Mzuri zarządzanie najmem](https://mzuri.pl/zarzadzanie-najmem/) · [PL app landscape (RSGN)](https://www.rsgn.pl/aplikacje-do-zarzadzania-najmem-ktore-warto-znac) · [RentSoft features](https://rentsoft.pl/funkcjonalnosci/) · [Rentio](https://rentio.com.pl/landing)
- Polish domain rules: [Rent Standard — czynsz vs opłaty vs media](https://www.rentstandard.pl/blog/czynsz-a-oplaty-i-media) · [Rendin — czynsz i opłaty administracyjne](https://rendin.pl/articles/jak-rozliczac-czynsz-i-oplaty-administracyjne-w-najmie) · [Mieszkanicznik — jak rozliczać media](https://mieszkanicznik.org.pl/jak-rozliczac-media-z-najemcami-zeby-nie-tracic-pieniedzy-i-nie-tworzyc-konfliktow/) · [Otodom — rozliczanie mediów](https://www.otodom.pl/wiadomosci/rozliczanie-mediow-w-umowie-najmu/) · [kluczo.pl — indeksacja czynszu](https://kluczo.pl/blog/indeksacja-czynszu) · [waloryzator.pl — najem instytucjonalny](https://waloryzator.pl/baza-wiedzy/waloryzacja-w-umowach-najmu-instytucjonalnego) · [kluczo.pl — najem okazjonalny](https://kluczo.pl/blog/umowa-okazjonalna)
- Open source / DDD: [microrealestate](https://github.com/microrealestate/microrealestate) · [GitHub topic: property-management](https://github.com/topics/property-management) · [GitHub topic: rental-management](https://github.com/topics/rental-management) · [Bounded contexts (Jovanović)](https://milanjovanovic.tech/blog/bounded-context-ddd-explained) · [Fowler — Bounded Context](https://martinfowler.com/bliki/BoundedContext.html)
