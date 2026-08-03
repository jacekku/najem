# Polish Residential Tenancy Law — Review of the NAJEM Domain Model

**Date:** 2026-08-03
**Reviewed artifact:** `docs/event-storming/session-2026-08-03-property-management.md`
**Scope of law reviewed (state as of 2026):**
- Ustawa z 21.06.2001 o ochronie praw lokatorów, mieszkaniowym zasobie gminy i o zmianie Kodeksu cywilnego ("**u.o.p.l.**") — incl. najem okazjonalny (art. 19a–19e) and najem instytucjonalny (art. 19f–19s)
- Kodeks cywilny ("**KC**"), art. 659–692 (lease)
- Prawo budowlane, art. 62, 64 (periodic technical inspections, building logbook)
- Rozporządzenie MSWiA z 21.11.2024 (smoke/CO detectors)
- Ustawa o zryczałtowanym podatku dochodowym (ryczałt on rental income)
- Ustawa o gospodarce nieruchomościami ("**u.g.n.**"), art. 184b–186 (professional property management)
- RODO/GDPR (Reg. 2016/679)

**Severity legend:** 🟥 **blocker** — model contradicts binding law or omits a hard legal deadline; 🟧 **should-fix** — legally required, workaround exists short-term; 🟨 **awareness** — protective / contextual.

---

## 0. The cross-cutting root cause: the Tenancy aggregate has no *legal form*

Almost every finding below branches on which of the three Polish residential lease regimes a tenancy is under:

| Regime | Governing rules | Deposit cap | Eviction | Who may use it |
|---|---|---|---|---|
| **Najem zwykły** (ordinary) | u.o.p.l. + KC | **12×** monthly rent (art. 6 ust. 1 u.o.p.l.) | Full court eviction proceedings | anyone |
| **Najem okazjonalny** | art. 19a–19e u.o.p.l. | **6×** monthly rent (art. 19a ust. 4) | Simplified (notarial submission-to-enforcement) — *only if registered with tax office* | natural person not renting as a business; fixed term ≤ 10 years |
| **Najem instytucjonalny** | art. 19f–19s u.o.p.l. | **3×** monthly rent (art. 19f ust. 4) | Simplified (notarial declaration, no replacement-premises indication needed) | entities renting as a business (this is likely the manager's own regime if the manager is the lessor) |

The model's `Tenancy` aggregate (`TenancyReserved` payload: unit, tenant(s), start date, negotiated rent, rent day, paymentReference) **carries no `legalForm` field**, so the system cannot validate deposit caps, cannot know which rent-increase rules apply, cannot know which termination constraints apply, and cannot know whether a tax-office registration deadline is ticking. → **🟥 Add `legalForm: {zwykly | okazjonalny | instytucjonalny}` (+ fixed-term/indefinite flag and agreed contractual notice clauses) to `TenancyReserved`.**

Sources: [art. 19a u.o.p.l. (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19a/), [art. 19f u.o.p.l. (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19f/), [okazjonalny vs instytucjonalny (GazetaPrawna)](https://prawo.gazetaprawna.pl/artykuly/1080465,najem-okazjonalny-i-najem-instytucjonalny-czym-sie-roznia.html), [art. 6 u.o.p.l. (arslege)](https://arslege.pl/kaucja-zabezpieczajaca/k23/a3817/).

---

## 1. Mistakes — where the model contradicts Polish law

### 1.1 🟥 `RentChangeScheduled` allows any decided/effective date pair — art. 8a + art. 9 u.o.p.l. heavily constrain rent increases (for najem zwykły)

**Model:** "Rent changes: yes — bitemporal: decided on day X, effective from day Y (e.g. +2 months)" and the Rent Change Process ("day before effective date → issue charge"). No notice-period, frequency, form, or justification constraints anywhere.

**Law (ordinary najem of a dwelling):**
- **Art. 8a ust. 1–2 u.o.p.l.:** the landlord may raise rent only by *wypowiedzenie dotychczasowej wysokości czynszu* (termination of the current rent level), **at the latest by the end of a calendar month**, in **writing under pain of nullity**, with a **3-month notice period** (longer if the contract says so). So "decided X, effective X+2 months" is **legally impossible** for ordinary tenancies — the earliest lawful effective date is the first day of the month following 3 full calendar months after written notice.
- **Art. 9 ust. 1b u.o.p.l.:** rent (and non-independent charges) may be increased **no more often than every 6 months**.
- **Art. 8a ust. 4:** on the tenant's written demand, the landlord must present the **justification and calculation in writing within 14 days**, otherwise the increase is **null**. Increases pushing rent above 3% of the reconstruction value of the premises per year must be objectively justified (art. 8a ust. 4a–4e); increases within last year's CPI are deemed justified (art. 8a ust. 4e).
- **Art. 8a ust. 5–6:** within **2 months** of receiving the notice the tenant may (a) contest the increase in court (increase frozen pending judgment) or (b) **refuse it in writing — which terminates the tenancy** at the end of the notice period. The model has no representation of "rent change caused tenancy end".

**Exception:** for **najem okazjonalny/instytucjonalny** art. 8a/9 largely do *not* apply (art. 19e, 19s u.o.p.l.) — the landlord may raise rent **only per an indexation/increase clause written into the agreement** (art. 19c ust. 1: raising rent otherwise than per the contract is not allowed). So the constraint set differs per legal form — another reason for finding 0.

**Impact on model:** `RentChangeScheduled` must validate (or at minimum warn — consistent with the "expert system with checks" principle): effective date ≥ notice date + statutory/contractual notice; last increase ≥ 6 months ago; and the Rent Change Process needs a branch for tenant refusal (`RentIncreaseRefusedByTenant` → tenancy ends at notice-period end, downstream to Tenancy Accounting) and for the 14-day justification demand. Decreases and mutually agreed annex changes are unconstrained — so distinguish `unilateralIncrease` vs `agreedChange` on the event.

Sources: [art. 8a (arslege)](https://arslege.pl/tryb-podwyzszenia-czynszu-przez-wlasciciela-lokalu/k23/a3827/), [art. 8a (lexlege)](https://lexlege.pl/ochr-pr-lok/art-8a/), [podwyżka czynszu — praktyka (domusglobal)](https://www.domusglobal.pl/post/podwyzka-czynszu-najmu-prawa), [wypowiedzenie wysokości czynszu (poradnikprzedsiebiorcy)](https://poradnikprzedsiebiorcy.pl/-wypowiedzenie-wysokosci-czynszu-jak-uchronic-sie-przed-podwyzkami).

### 1.2 🟥 `TenancyEnded` models landlord termination as a free action with a comment — art. 11 u.o.p.l. makes it a closed catalogue with mandatory written form and long procedures

**Model:** "unnatural termination — generic, with end date + reason comment (damages, eviction, etc.). No detailed eviction/termination taxonomy for MVP." `EndTenancy` command: end date, reason type (`natural | terminated | error-annulled`), comment, backToMarket.

**Law:** For any dwelling occupied for payment, the landlord may terminate **only** for the reasons enumerated in **art. 11 ust. 2–5 u.o.p.l.**, **in writing under pain of nullity, stating the reason**. Key cases (art. 11 ust. 2, notice by month-end, ≥ 1 month):
1. tenant uses the premises contrary to the contract despite a written warning (upomnienie);
2. **rent arrears of at least 3 full payment periods**, *and only after* a **written notice of intent to terminate granting an extra month to pay** arrears + current dues;
3. subletting/lending the unit without required consent;
4. premises require vacation due to demolition/major renovation.
Own-use termination requires **6-month notice** (replacement premises provided) or **3-year notice** (none provided) — art. 11 ust. 4–5. A "reason comment" free-text does not satisfy any of this; "damages" alone is not even a lawful ground. Tenant-side termination of an indefinite lease: KC art. 688 (3 months, month-end). **Fixed-term leases** (the dominant case here) can be unilaterally terminated **only in the cases explicitly written into the agreement** (KC **art. 673 § 3**) — a fixed-term lease with no termination clause simply cannot be "ended early" unilaterally by either side. Okazjonalny/instytucjonalny expiry/termination then has its own vacate-demand path (art. 19d, 19i): written demand with officially certified signature, ≥ 7 days to vacate, then enforcement clause on the notarial declaration.

**Impact on model:** the *event* can stay generic (the manager records what happened in the world — the system is not the notary), but:
- the `reason` needs a **legally meaningful taxonomy**, not free text: `agreementExpiry | mutualAgreement (porozumienie stron) | tenantNotice | landlordNotice(art11ground) | rentIncreaseRefusal | vacateDemandOkazjonalny | courtEviction | error-annulled`. "Mutual agreement" is in practice the main lawful early-exit and is entirely absent from the model.
- the **arrears path is a multi-step statutory process** (see Missing #2.3) that the current single-shot `TenancyEnded` cannot represent;
- `TenancyEndingSoon` fires at a **fixed 1 month** — but lawful landlord terminations have 1-month, 6-month or 3-year horizons, and the arrears procedure needs its own clock. The 1-month YAGNI is fine for natural expiry, wrong as the only lead time.

Sources: [art. 11 (arslege)](https://arslege.pl/wypowiedzenie-umowy-najmu-przez-wlasciciela-lokalu/k23/a3831/), [art. 11 (lexlege)](https://lexlege.pl/ochr-pr-lok/art-11/), [art. 11 full text (gofin)](https://przepisy.gofin.pl/przepisy,6,29,221,3893,387235,20230418,art-11-ustawa-z-dnia-21062001-r-o-ochronie-praw-lokatorow.html), [wypowiedzenie z powodu zaległości (nieruchomosci-online)](https://www.nieruchomosci-online.pl/porady/wypowiedzenie-umowy-najmu-z-powodu-zaleglosci-czynszowych-23806.html), [art. 673 KC (arslege)](https://arslege.pl/terminy-i-sposob-wypowiedzenia-najmu/k9/a5678/), [wypowiedzenie umowy na czas określony (stasik-kancelaria)](https://www.stasik-kancelaria.pl/wypowiedzenie-umowy-najmu-na-czas-okreslony/).

### 1.3 🟥 Deposit ("normal deposit invoice" on activation; lifecycle deferred) — caps, a hard 1-month return deadline, and statutory valorization are already binding

**Model:** "Deposit: large topic, deferred to another session"; Tenancy Start Process: "`TenancyActivated` → Tenancy Accounting: deposit + rent invoices"; end-of-tenancy checklist ships a generic "everything paid off ✓".

**Law:**
- **Caps** (see table in §0): 12× / 6× / 3× monthly rent at signing (art. 6 ust. 1, art. 19a ust. 4, art. 19f ust. 4 u.o.p.l.). A deposit invoice above the cap for the tenancy's legal form is unlawful.
- **Return deadline:** deposit must be returned **within 1 month of the tenant vacating the unit** (art. 6 ust. 4 u.o.p.l.; same rule for okazjonalny via art. 19a ust. 5 and instytucjonalny via art. 19f ust. 5), less documented landlord claims. Nothing in the model starts this clock at `TenancyEnded` — a system that prompts a checklist "what to do with the unit" but not "return the deposit by date D" misses the one deadline with litigation risk attached.
- **Valorization** (najem zwykły): the returned deposit equals the *multiple of the current rent at return* that was taken at signing, not less than the nominal amount (art. 6 ust. 3 u.o.p.l.). I.e. if 2× rent was taken and rent has risen, 2× the *new* rent is owed back. The deposit is therefore **not a static amount** — the Accounting session must model it as (multiplier × rent-at-return), which interacts with `RentChangeScheduled`.

**Impact on model:** even with the deposit session deferred, `TenancyEnded` must emit/trigger a **DepositReturnDue(deadline = vacateDate + 1 month)** policy, and `TenancyReserved` should capture `depositMultiplier`/`depositAmount` so caps can be validated against `legalForm`.

Sources: [art. 6 u.o.p.l. (arslege)](https://arslege.pl/kaucja-zabezpieczajaca/k23/a3817/), [art. 6 (lexlege)](https://lexlege.pl/ochr-pr-lok/art-6/), [zwrot kaucji — termin 30 dni (kluczo)](https://kluczo.pl/blog/zwrot-kaucji-termin), [waloryzacja kaucji (BIP Kraków)](https://www.bip.krakow.pl/?dok_id=28861), [kaucja w najmie (rankomat)](https://rankomat.pl/nieruchomosci/kaucja-w-umowie-najmu-mieszkania).

### 1.4 🟧 Handover protocol as an optional free-form checklist item — art. 6c u.o.p.l. makes it the *legal basis of end-of-tenancy settlement*

**Model:** pre-activation checklist has "damage protocol done" as one free-form item among "keys handed over, pictures taken…", and activation can proceed *late* whenever the checklist completes; no protocol artifact exists in the domain.

**Law:** **Art. 6c u.o.p.l.:** *before* handing the unit over, the parties draw up a **protokół zdawczo-odbiorczy** recording technical condition and degree of wear of installations/equipment; that protocol **is the basis for settlements at return** (deductions from the deposit, damage claims). Art. 6e defines the return standard (renovation obligations net of normal wear). For najem okazjonalny the protocol-equivalent is doubly important because the required attachments (art. 19a ust. 2) and the vacate procedure presuppose documented condition. Without a first-class protocol document, every deposit deduction the Accounting context will ever make is evidentially unsupported.

**Impact on model:** promote the protocol from a checklist string to a domain document: `HandoverProtocolRecorded {tenancyId, type: moveIn|moveOut, meterReadings, conditionNotes, photos(S3 refs), signedCopy(S3 ref), date}` — at move-in (feeding the Tenancy Start Process gate) and at move-out (feeding deposit settlement). The S3 document storage already in the model makes this cheap.

Sources: [art. 6c (arslege)](https://arslege.pl/obowiazek-sporzadzenia-protokolu-odbiorczego/k23/a3820/), [art. 6c (lexlege)](https://lexlege.pl/ochr-pr-lok/art-6c/), [art. 6e (lexlege)](https://lexlege.pl/ochr-pr-lok/art-6e/).

### 1.5 🟧 `TenancyReserved` = "agreement signed", activation "as soon as the checklist completes (late start)" — for najem okazjonalny the *registration clock* runs from lease start, not from your activation event

The model's `TenancyActivated` is a system state ("tenant may move in"), possibly days after the contractual start date (late checklist). The **14-day tax-office registration deadline** for najem okazjonalny (art. 19b ust. 1 u.o.p.l.) runs from **rozpoczęcie najmu** — the lease commencement under the agreement — not from when the manager flips the state. If the system derives the deadline from `TenancyActivated` (late), the registration can silently miss the window and the owner **loses the simplified eviction regime** (art. 19b ust. 3 → art. 19c–19d protections don't apply; the tenancy falls back under full u.o.p.l. tenant protection). Derive the deadline from the **agreement start date carried on `TenancyReserved`**. (Details of the registration process itself: Missing #2.1.)

Sources: [art. 19b (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19b/), [najem okazjonalny a urząd skarbowy (PIT.pl)](https://www.pit.pl/podatek-dochodowy/najem-okazjonalny-a-formalnosci-w-urzedzie-skarbowym-1005585), [najem okazjonalny 2026 (infor)](https://www.infor.pl/prawo/umowy/najem-i-dzierzawy/7603328,najem-okazjonalny-2026-zasady-wymagane-dokumenty-i-obowiazki-wlasciciela.html).

### 1.6 🟨 "THE ONLY hard constraint: no overlapping tenancies" + expert-system freedom — legally fine *inside* PM, but unlawful states must not leak to Accounting

The expert-system stance (manager can move states freely) is compatible with the law — the law binds the *parties*, not the record-keeping tool. The risk is the automated boundary: policies that **auto-issue charges** (Rent Change Process) or **auto-generate invoices** (Tenancy Start Process) turn an unlawful record into a real-world demand for money (e.g. invoicing a rent increase that was never lawfully noticed, or a deposit above the cap). Keep manager freedom, but put **compliance checks on the PM → Tenancy Accounting translation layer** (the ACL is exactly the right seam for this): warn-and-confirm before issuing a charge that violates art. 8a/9/6. This also matches the session's own "some checks/confirmations" caveat.

Also note KC **art. 661 § 1**: a lease for longer than 10 years is treated as indefinite after year 10 (and okazjonalny is capped at 10 years outright) — a trivial validation on `TenancyReserved` dates. Sources: [art. 661 KC (lexlege)](https://lexlege.pl/kc/art-661/), [najem okazjonalny max 10 lat (GazetaPrawna)](https://edgp.gazetaprawna.pl/prawo/prawo-cywilne/artykuly/10488352,najem-okazjonalny-maksymalnie-na-dziesiec-lat.html).

---

## 2. Missing law-required processes

### 2.1 🟥 Najem okazjonalny: tax-office registration within 14 days + document set tracking

- **Legal basis:** art. 19b ust. 1 u.o.p.l. (registration with the naczelnik urzędu skarbowego competent for the **owner's** residence, within **14 days of lease commencement**; tenant may demand proof); art. 19a ust. 2 (required attachments: tenant's **notarial declaration of submission to enforcement**; tenant's indication of replacement premises; **owner-of-replacement-premises consent** declaration); art. 19a ust. 3 (if the tenant loses the right to the replacement premises, they must indicate new ones **within 21 days**, else the landlord may terminate with 7 days' notice); art. 19b ust. 3 (no registration ⇒ no simplified regime).
- **Required process:** on signing (`TenancyReserved`) of an okazjonalny tenancy: (1) verify the three attachments exist before the unit is handed over; (2) start a 14-day registration deadline from the agreement start date; (3) store the registration confirmation; (4) monitor replacement-premises validity.
- **Suggested additions:** `legalForm` on `TenancyReserved`; events `TenancyRegisteredWithTaxOffice {date, confirmationDoc}`, `NotarialDeclarationAttached`, `ReplacementPremisesIndicated` / `ReplacementPremisesLost` (starts 21-day clock); policy "okazjonalny + startDate reached + not registered ⇒ escalating prompt"; the document trio as **gating items** on the pre-activation checklist (typed, not free-form).

Sources: [art. 19a (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19a/), [art. 19b (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19b/), [poradnik najem okazjonalny (kluczo)](https://kluczo.pl/blog/umowa-okazjonalna), [krok po kroku (nieruchomoscizpolecenia)](https://nieruchomoscizpolecenia.pl/najem-okazjonalny-krok-po-kroku/).

### 2.2 🟥 Building periodic inspections (przeglądy okresowe) — a recurring, property-level statutory process the model lacks entirely

- **Legal basis:** **Prawo budowlane art. 62 ust. 1:** the owner **or manager (zarządca)** must have performed: (a) **annually** — inspection of building elements exposed to weather, environmental-protection installations, **gas installations and chimney/ventilation flues (przewody kominowe)**; (b) **every 5 years** — full technical condition + **electrical and lightning-protection installation** tests. Rozporządzenie MSWiA on fire protection: **flue cleaning** every 3 months (solid fuel) / 6 months (liquid/gas fuel), ventilation ducts annually. Results go into the **building logbook** (książka obiektu budowlanego, art. 64 — now the digital **c-KOB**). Non-compliance is a fined offence (art. 93 pkt 8) and, for the professional manager, personal liability. This is squarely the manager's job for the modeled "one property, floors 3–5 under construction" case.
- **Required process:** recurring compliance calendar per **Property** (and per installation type), evidence storage, next-due-date computation from last-done.
- **Suggested additions:** a small **Compliance** concept in the Property Management context (or a `PropertyCompliance` aggregate): `InspectionScheduled` / `InspectionCompleted {property, type: annual|fiveYear|chimney|gas|electrical|flueCleaning, date, reportDoc(S3), findings}` + policy "inspection overdue ⇒ prompt manager" (same mechanism as `TenancyEndingSoon`). Feed it into the property-level Timeline read model — it is exactly the "problems" lane the domain expert asked for.

Sources: [art. 62 Prawo budowlane (lexlege)](https://lexlege.pl/prawo-budowlane/art-62/), [art. 62 (sip.lex, t.j. Dz.U.2026.524)](https://sip.lex.pl/akty-prawne/dzu-dziennik-ustaw/prawo-budowlane-16796118/art-62), [omówienie kontroli okresowych (barometrprawa)](https://barometrprawa.pl/artykul/art-62-prawo-budowlane/), [przegląd kominiarski 2026 (infor)](https://www.infor.pl/prawo/umowy/dom/6567246,przeglad-kominiarski-w-2026-roku-jak-czesto-trzeba-robic-ile-kosztuje-i-jaka-jest-kara-za-brak-przegladu-jak-sprawdzic-uprawnienia-kominiarza.html), [e-protokół i c-KOB (świat kominów)](https://swiat-kominow.pl/blog/artykul-przeglad-kominiarski-ceeb-2026/).

### 2.3 🟥 Arrears-driven termination workflow (the statutory 3-periods + warning + 1-month-cure sequence)

- **Legal basis:** art. 11 ust. 2 pkt 2 u.o.p.l. — termination for arrears is valid **only** after: arrears ≥ **3 full payment periods** → **written warning of intent to terminate + additional 1-month payment window** → only then written termination (month-end, ≥ 1 month notice). Skipping a step voids the termination.
- **Required process:** the session already routes arrears to Accounting ("bright red = chase arrears"), but the *legal consequence chain* is a PM/Tenancy concern: the system must know a warning was sent and when the cure window lapses, because that is what makes the eventual `TenancyEnded(reason: landlordNotice)` valid.
- **Suggested additions:** a fourth process manager, **Arrears Termination Process**: input from Accounting (`ArrearsReached3Periods`) → command `IssueArrearsWarning` → event `ArrearsWarningSent {date, cureDeadline}` → on cure: `ArrearsCured`; on lapse: manager prompted to `GiveNoticeOfTermination` → `TerminationNoticeGiven {ground, noticeDate, effectiveDate}` → feeds `TenancyEndingSoon` → `TenancyEnded`. This also gives `TenancyEnded` the lawful-ground evidence trail from finding 1.2.

Sources: [art. 11 (arslege)](https://arslege.pl/wypowiedzenie-umowy-najmu-przez-wlasciciela-lokalu/k23/a3831/), [praktyka wypowiedzenia za zaległości (nieruchomosci-online)](https://www.nieruchomosci-online.pl/porady/wypowiedzenie-umowy-najmu-z-powodu-zaleglosci-czynszowych-23806.html).

### 2.4 🟧 Rent-increase notice workflow (the lawful path that `RentChangeScheduled` should sit inside)

- **Legal basis:** art. 8a ust. 1–6, art. 9 ust. 1b u.o.p.l. (najem zwykły); art. 19c ust. 1 (okazjonalny — contract clause only). Details in finding 1.1.
- **Required process:** written notice → 3-month clock → tenant reaction window (2 months: accept / demand justification (14-day landlord duty) / refuse ⇒ tenancy terminates / sue ⇒ increase frozen) → effective date → charge issued.
- **Suggested additions:** `RentIncreaseNoticeGiven {noticeDoc, effectiveDate}` preceding `RentChangeScheduled`; `RentIncreaseJustificationDemanded` (+ 14-day deadline task); `RentIncreaseRefusedByTenant` → policy: schedule `TenancyEnded(reason: rentIncreaseRefusal, endDate = notice-period end)`; validation/warning: effective date and 6-month frequency per legal form. The existing "day-before-effective-date" charge policy stays — it just must consume a *lawful* effective date.

### 2.5 🟧 Deposit return deadline process

- **Legal basis:** art. 6 ust. 3–4, art. 19a ust. 5, art. 19f ust. 5 u.o.p.l. (1 month from vacating; valorized amount; documented deductions only — with the art. 6c protocol as the settlement basis).
- **Required process:** `TenancyEnded` (with actual vacate date, which may differ from the contractual end date) ⇒ deadline task "settle deposit by D+1 month"; deduction evidence = move-out `HandoverProtocolRecorded` vs move-in protocol.
- **Suggested additions:** even before the Accounting/deposit session: event `DepositSettlementDue {tenancyId, deadline}` emitted by the End-of-Tenancy Process; end-of-tenancy checklist gets a **typed** deadline item, not just "everything paid off ✓". The deposit session should adopt: `depositMultiplier` captured at `TenancyReserved`, cap validation vs `legalForm`, valorization on return.

### 2.6 🟧 Owner tax reporting cycle (ryczałt) — Accounting-session input, but the deadlines shape a monthly process

- **Legal basis:** ustawa o zryczałtowanym podatku dochodowym — private rental income is mandatorily taxed as **ryczałt: 8.5% up to 100 000 zł of annual revenue, 12.5% above** (limit per taxpayer; spouses have a shared limit); tax **payable monthly by the 20th** of the following month on *received* (cash-basis) rent; annual **PIT-28** filed 15 Feb–30 Apr. Utilities/media borne by the tenant per the contract are excluded from the tax base — which means the invoice/charge model must **separate rent from media/opłaty niezależne line items** (this also matters for art. 9 ust. 2 u.o.p.l. — opłaty niezależne pass-through rules).
- **Required process:** per-owner (respecting `PropertyOwnershipChanged` percentage shares) monthly received-rent report by the 20th; per-owner annual revenue counter crossing 100 000 zł (rate switch mid-year); rent vs media split on every charge.
- **Suggested additions (for the Accounting session):** read model "owner monthly ryczałt base"; charge line-item taxonomy `{rent, media, deposit, repair-recharge}` — note the session already decided invoices flow "create charge for contact X, line items Y", so only the taxonomy is missing. Receipts: the tenant may always demand a **pokwitowanie** (KC art. 462); residential long-term rent is **VAT-exempt** (art. 43 ust. 1 pkt 36 ustawy o VAT) — "invoice generation" should produce zw-VAT documents, not just internal charges.

Sources: [PIT za wynajem 2026 (e-pity)](https://www.e-pity.pl/pit-za-wynajem/), [ryczałt od najmu 2026 (lukaszziemianski)](https://lukaszziemianski.pl/blog/ryczalt-od-najmu-2026/), [terminy i pułapka limitu (nowyswiatnieruchomosci)](https://nowyswiatnieruchomosci.pl/ryczalt-od-najmu-prywatnego-2026-termin-pit-28-i-pulapka-limitu-100-tys-zl/), [podatek od wynajmu — przewodnik (prodoma)](https://prodoma.pl/podatek-od-wynajmu-mieszkania-2026-ryczalt-85-terminy-i-przewodnik-krok-po-kroku).

### 2.7 🟧 RODO for the Contacts context — especially the **Leads/Applicants pool**

- **Legal basis:** RODO art. 6(1)(b) (contract/pre-contract steps), art. 6(1)(c) (legal obligations), art. 6(1)(f) (legitimate interest — debt recovery), art. 13 (information duty), art. 5(1)(c),(e) (minimization, storage limitation), art. 28 (processors). A manager renting as a business is unambiguously a **data controller** (or processor for the owners — decide!).
- **What the model gets right for free:** tenancy-execution data needs **no consent** (art. 6(1)(b)).
- **What's missing:**
  1. **Leads pool is pre-contractual at best.** "Applicants can exist regardless of unit state — e.g. 'I'll start in a year'" exceeds art. 6(1)(b) pre-contract scope once the concrete inquiry dies; keeping a person in a pool *for future offers* is marketing-adjacent ⇒ needs **consent or a documented legitimate-interest assessment + a retention period**. `LeadRegistered` must carry `{lawfulBasis, infoClauseServedAt, retainUntil}`.
  2. **Retention/erasure:** no deletion concept exists anywhere. Needed: policy "lead inactive > N months ⇒ prompt/erase" (`LeadDataErased`), and post-tenancy retention tied to claim limitation (typ. up to 6 years for owner claims / 3 years business-to-consumer, plus 5 tax years for accounting docs). Event-sourcing note: **an event-sourced Contacts stream needs an erasure strategy** (crypto-shredding or PII-in-lookaside-store) — decide *before* the store format ossifies.
  3. **Information clause** at first capture (the Unit Board "capture the interested person" flow is exactly where art. 13 bites — phone-call capture still requires serving the clause, e.g. by SMS/e-mail follow-up).
  4. **Processor agreements** (art. 28) with S3 provider, Keycloak host, and — if the manager processes for owners — **umowa powierzenia** owner⇄manager.
  5. **Minimization:** don't collect PESEL/ID-scan at lead stage; guarantor data has the same duties.
- **Suggested additions:** fields on `LeadRegistered`; events `PersonalDataErasureRequested` / `LeadDataErased` / `ContactRetentionExpired`; a retention policy document per contact role (lead / tenant / guarantor).

Sources: [RODO na rynku najmu (propertynews)](https://www.propertynews.pl/prawo/rodo-na-rynku-najmu-jak-przetwarzac-dane-osobowe,66707.html), [RODO w najmie (freedom-development)](https://freedom-development.pl/dla-wlasciciela-i-inwestora/obsluga-najmu/rodo-najem/), [RODO i wynajem (wynajmistrz)](https://wynajmistrz.pl/rodo-wynajem-mieszkania-dla-wynajmujacych/), [jakie dane najemcy (ada.place)](https://ada.place/blog/rodo-w-umowach-najmu-i/), [czy podlegasz pod RODO (cno-legal)](https://www.cno-legal.pl/czy-wynajmujac-mieszkanie-podlegasz-pod-rodo/).

### 2.8 🟧 Smoke & CO detectors — new recurring obligation, deadlines inside this system's lifetime

- **Legal basis:** rozporządzenie MSWiA z 21.11.2024 (fire protection of buildings): autonomous **smoke detectors and CO detectors** — mandatory from **30.06.2026** for premises providing accommodation services (hotels, short-term rental), and from **1.01.2030 for all dwellings** — including long-term rentals; fines up to 30 000 zł discussed for non-compliance in rental settings.
- **Required process:** per-Unit equipment record (installed? battery/service date) — foldable into the compliance calendar of #2.2.
- **Suggested additions:** `DetectorInstalled {unit, type: smoke|CO}` or simply typed items in the Property/Unit compliance checklist; a 2030 (or 2026 if any unit is let short-term) readiness report.

Sources: [terminy MSWiA (smoxy)](https://smoxy.pl/obowiazek-czujnika-czadu-przepisy/), [od 30.06.2026 w najmie krótkoterminowym, kary do 30 tys. (GazetaPrawna)](https://www.gazetaprawna.pl/biznes/nieruchomosci/artykuly/11267207,od-30-czerwca-2026-r-obowiazkowe-czujniki-dymu-w-mieszkaniach-na-wynajem-kary-do-30-tys-zl.html), [które budynki i kiedy (rp.pl)](https://www.rp.pl/nieruchomosci/art43369661-nowe-obowiazki-dla-wlascicieli-nieruchomosci-ktore-budynki-obejma-w-2026-roku).

### 2.9 🟨 Repair duty split — art. 6a/6b u.o.p.l. pre-answers "who pays" for the Repair aggregate

The session deferred payment attribution to Accounting, but the law already allocates it: **art. 6a** (landlord: building, common areas, technical installations, replacement of windows/doors/floors/plaster, internal installations up to fittings) vs **art. 6b** (tenant: maintenance and minor repairs — floors' upkeep, painting, fittings, appliances…). The `RepairReported` event should carry a `statutoryDutyHint: {landlord | tenant | negotiable}` so the future Accounting session doesn't re-derive it. Also **art. 10 u.o.p.l.**: the tenant must grant access for inspections/repairs after prior notice (emergency entry rules) — a natural notification touchpoint if repairs get scheduling later. Source: [obowiązki najemcy i wynajmującego (adwokat-sobolewski)](https://adwokat-sobolewski.pl/obowiazki-najemcy-i-wynajmujacego-lokal-mieszkalny/), [rozdz. 2 u.o.p.l. (lexlege)](https://lexlege.pl/ochr-pr-lok/rozdzial-2-prawa-i-obowiazki-wlascicieli-i-lokatorow/1115/).

### 2.10 🟨 Professional management contract obligations (if NAJEM's operator is a zarządca under u.g.n.)

If the manager manages properties **for third-party owners as a business**, u.g.n. applies: **art. 184b–185** (management contract must be in **writing/electronic form under pain of nullity**) and **art. 186** (mandatory **OC professional-liability insurance**; a **copy of the policy is an attachment to every management contract**; the manager must notify owners of policy changes; owners may terminate immediately if no valid policy after a written call). The model treats "Owner" as a report recipient only — fine — but the system should at least store the management agreement + current OC policy per owner relationship and their validity dates. Sources: [art. 186 u.g.n. (arslege)](https://arslege.pl/obowiazkowe-ubezpieczenie-odpowiedzialnosci-cywilnej-zarzadcy-nieruchomosci/k46/a11490/), [art. 186 (lexlege)](https://lexlege.pl/ustawa-o-gospodarce-nieruchomosciami/art-186/).

---

## 3. Nice-to-have compliance features (protective, not mandatory)

1. 🟨 **Legal-clock dashboard.** One read model listing every running statutory deadline (14-day registration, 1-month deposit return, 3-month rent notice, arrears cure windows, inspection due dates, 21-day replacement-premises). The Timeline projection infrastructure already planned can host it.
2. 🟨 **Document evidence vault with delivery proof.** Art. 8a and 11 notices are void without written form — store the signed notice + proof of delivery (potwierdzenie nadania/odbioru) on the Tenancy stream (`NoticeDocumentFiled`). Turns disputes from "he-said" into an audit trail; S3 storage is already in the model.
3. 🟨 **Compliance validation on the ACL, not the aggregates.** Preserves the expert-system principle: PM lets the manager do anything; the Tenancy-Accounting translation layer warns/blocks money-generating commands that would rest on an unlawful act (finding 1.6).
4. 🟨 **Rent-increase calculator:** given legal form + last increase date + CPI, propose the earliest lawful effective date and flag >3%-of-reconstruction-value increases needing justification (art. 8a ust. 4a).
5. 🟨 **Okazjonalny health check per tenancy:** registered? notarial declaration on file? replacement premises still valid? — a green/red badge next to the payment-status badge the manager already wants.
6. 🟨 **Phantom-tenancy exclusion** (already accepted in the session): `error-annulled` reason keeps occupancy stats and *tax reports* clean — mistakenly invoiced rent otherwise looks like taxable revenue.
7. 🟨 **CEEB touchpoint:** heat-source declarations (deklaracja CEEB) must be updated within 14 days of a change of heat source — worth a checkbox on Property if boilers get replaced. ([c-KOB/CEEB context](https://swiat-kominow.pl/blog/artykul-przeglad-kominiarski-ceeb-2026/))
8. 🟨 **Guarantor documentation:** surety (poręczenie, KC art. 876 §2) requires the guarantor's **written declaration under pain of nullity** — store it as a typed document, since the model already attaches guarantors to tenancies.

---

## Summary table

| # | Finding | Severity | Model element affected |
|---|---|---|---|
| 0 | No `legalForm` on Tenancy | 🟥 | `TenancyReserved`, Tenancy aggregate |
| 1.1 | Bitemporal rent change ignores art. 8a/9 (3-mo notice, 6-mo frequency, refusal right) | 🟥 | `RentChangeScheduled`, Rent Change Process |
| 1.2 | Free landlord termination vs art. 11 closed catalogue / KC 673 §3 | 🟥 | `TenancyEnded`, `EndTenancy`, `TenancyEndingSoon` |
| 1.3 | Deposit caps / 1-month return / valorization unmodeled | 🟥 | Tenancy Start & End processes, deposit session |
| 2.1 | Okazjonalny 14-day tax registration + document set | 🟥 | New events, pre-activation checklist |
| 2.2 | Art. 62 building inspections calendar | 🟥 | New Property compliance concept |
| 2.3 | Arrears warning + cure workflow | 🟥 | New Arrears Termination Process |
| 1.4 | Handover protocol as first-class document (art. 6c) | 🟧 | Checklists, new `HandoverProtocolRecorded` |
| 1.5 | Registration clock from agreement start, not activation | 🟧 | Tenancy Start Process |
| 2.4 | Rent-increase notice workflow | 🟧 | Rent Change Process |
| 2.5 | Deposit settlement deadline task | 🟧 | End-of-Tenancy Process |
| 2.6 | Ryczałt monthly cycle; rent vs media line items; VAT-zw invoices | 🟧 | Accounting session input |
| 2.7 | RODO: leads-pool basis, retention, erasure in event store | 🟧 | Contacts context, `LeadRegistered` |
| 2.8 | Smoke/CO detectors (2026/2030) | 🟧 | Unit/Property compliance |
| 2.9 | Art. 6a/6b repair duty split hint | 🟨 | `RepairReported` |
| 2.10 | u.g.n. management contract + OC insurance | 🟨 | Owner relationship |
| 3.x | Legal-clock dashboard, evidence vault, ACL validation, calculators | 🟨 | Read models / ACL |
