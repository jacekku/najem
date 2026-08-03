# Accounting Law Review — NAJEM Accounting Session

**Date:** 2026-08-03 · **Status of law:** as of August 2026
**Scope:** legal/tax obligations bearing on the Accounting domain (ledgers, documents, tax hand-off, retention). Setting: zarządca under written management contract; owner = physical person (najem zwykły, ryczałt), soon a fundacja (rodzinna vs zwykła covered); system is **not** the official books — it feeds the owners' accountants.

Severity legend: **S1** = statutory duty with sanction/litigation risk, ledger must enforce · **S2** = statutory duty, ledger must support producing it · **S3** = awareness / design-shaping.

---

## 1. Hard obligations our ledgers must satisfy

### S1 — Cash-basis, per-owner, component-split revenue (ryczałt engine input)
- Najem prywatny of a physical person is taxed **exclusively** by ryczałt od przychodów ewidencjonowanych (art. 2 ust. 1a + art. 6 ust. 1a ustawy z 20.11.1998 o zryczałtowanym podatku dochodowym, t.j. Dz.U. 2024 poz. 776; the only form since 2023). Rates: **8.5% up to 100 000 zł of annual revenue, 12.5% on the excess** (art. 12 ust. 1 pkt 4 lit. a). Tax payable **monthly by the 20th** of the following month (quarterly option exists), December by 20 January (art. 21).
- Revenue is **cash-basis**: money *received or put at disposal* (art. 6 ust. 1a). Our reconciliation events (`PaymentReceived`/allocation), not `ChargeCreated`, define the tax point. This confirms F5: the report must be built from **received** amounts, keyed by **value date of the bank credit**.
- **Media/pass-through exclusion**: consistent KIS line — amounts the tenant is contractually obliged to bear for media/administracja, where the landlord is only an intermediary settling **without markup at actual cost**, are **not** the landlord's revenue. Condition: the written agreement must literally split czynsz vs opłaty; tax office reads the contract text. → the mandatory monthly-total-with-optional-breakdown from PM (`TenancyReserved.{rent, adminFee, mediaAdvance}`) is exactly what keeps the ryczałt base clean; **charges without a breakdown risk the whole amount being taxable**.
  Sources: [poradnikprzedsiebiorcy — opodatkowanie najmu prywatnego](https://poradnikprzedsiebiorcy.pl/-opodatkowanie-najmu-prywatnego), [infor — media a ryczałt, zapis w umowie](https://www.infor.pl/twoje-pieniadze/podatki/7559360,najem-prywatny-i-ryczalt-jeden-zapis-w-umowie-zeby-nie-przeplacac-za.html), [podatki.biz — kiedy media są przychodem](https://www.podatki.biz/artykuly/najem-prywatny-kiedy-media-sa-przychodem-wszystko-zalezy-od-umowy_52_54477.htm), [ilezametr — ryczałt 2026](https://ilezametr.pl/jaki-podatek-od-wynajmu-mieszkania-ryczalt-2026/).
- No statutory *ewidencja przychodów* is required for najem prywatny (**art. 15 ust. 3** of the ryczałt act exempts art. 6 ust. 1a revenue), but revenue must be provable from receipts/transfers — our ledger *is* that evidence in practice. Source: [GOFIN — art. 15](https://przepisy.gofin.pl/przepisy,6,42,42,710,24230,20120101,art-15-ustawa-z-dnia-20111998-r-o-zryczaltowanym-podatku.html), [lex.pl — ryczałt od najmu prywatnego](https://www.lex.pl/ryczalt-od-najmu-prywatnego,25615.html).

### S1 — Deposit rules (corrects F1's cap table)
- Caps per legalForm: najem **zwykły 12×** miesięczny czynsz (art. 6 ust. 1 u.o.p.l.), **okazjonalny 6×** (art. 19a ust. 4), **instytucjonalny 6× — not 3×** (art. 19f ust. 5; raised from 3× in 2019). **F1's "12×/6×/3×" must become "12×/6×/6×".**
- Return **within 1 month of vacating** (art. 6 ust. 4; art. 19f ust. 6 for instytucjonalny), after documented deductions. Valorized refund formula (multiplier × rent-at-return, never below nominal) per art. 6 ust. 3 — F1 stands.
  Sources: [lexlege — art. 19f u.o.p.l.](https://lexlege.pl/ochr-pr-lok/art-19f/), [nieruchomosclokalowa — kaucja](https://nieruchomosclokalowa.pl/kaucja-w-umowie-najmu-co-mozna-z-niej-pokryc-oraz-zasady-wplaty-i-zwrotu-kaucji/).

### S1 — Only czynsz + opłaty niezależne may be charged (charge-taxonomy constraint)
- Art. 9 ust. 5 u.o.p.l.: in najem, apart from czynsz the landlord may collect **only opłaty niezależne od właściciela** (media: energy, gas, water, sewage, waste). Art. 9 ust. 6: and only where the tenant has **no direct contract** with the supplier.
- Consequence for F2's taxonomy: `adminFee` (czynsz administracyjny do wspólnoty) is **not** an opłata niezależna — legally it must sit inside czynsz (or be a contractual pass-through the tenant explicitly assumes). The ACL warn-gate should flag standalone charge lines that are neither czynsz nor media.
  Sources: [lexlege — art. 9 u.o.p.l.](https://lexlege.pl/ochr-pr-lok/art-9/), [arslege — art. 9](https://arslege.pl/dopuszczalna-czestotliwosc-podwyzszania-czynszu-i-oplat/k23/a3828/).

### S1 — Media advances must be settled against actual cost
- Advances (`mediaAdvance`) are zaliczki: on a rise of opłaty niezależne the landlord must present the tenant a **written zestawienie of the charges with the reason** for the increase, and the tenant owes **only what is necessary to cover the actual delivery costs** (art. 9 ust. 2 u.o.p.l.). Overcollection is refundable; true-up both ways is the legal norm — F3's `MediaSettlementPrepared` (charge **or credit**) is the right shape, sourced from `HandoverProtocolRecorded` meter readings and supplier invoices.
- The ledger must retain, per settlement: supplier invoices, meter readings, allocation key, advance total vs actual — that bundle is what a tenant (or court) can demand.
  Source: [lexlege — art. 9 u.o.p.l.](https://lexlege.pl/ochr-pr-lok/art-9/).

### S2 — Zarządca duties (u.g.n., t.j. Dz.U. 2024 poz. 1145)
- Written or electronic **management contract pod rygorem nieważności** (art. 185 ust. 2); **mandatory OC insurance**, copy attached to the contract (art. 186); operating without OC → administrative fine (art. 198b). Matches v1.1 C8 (store contract + polisa per owner relationship).
- **Rent-mirror reporting (art. 186a)**: the zarządca must report to the gmina data on residential rents from lease agreements in managed buildings (rent linked to location, building age/condition, area, standard), on the schedule set by rozporządzenie (half-yearly zestawienia). → cheap win: a **`RentMirrorReportExtracted`** projection over active tenancies; the data (rent, area, standard) already exists in PM.
- **Client-money separation: there is NO statutory trust/escrow account regime** for a Polish zarządca (the professional standards that implied per-property accounts were repealed in the 2013 deregulation; today only the contract governs). Separation of owner money, deposit money and operator money is **contractual best practice**, strongly recommended (and assumed by Q4's "deposits likely have a separate account"), but not law. Deposits are the **owner's debt to the tenant**; the manager holds them purely as agent. Design consequence: multi-bank-source model per Q4 is a business choice we can shape freely — but the ledger must always answer "whose money is this" (F4) because commingling has civil-liability (OC) exposure, not statutory-accounting exposure.
  Sources: [lexlege — art. 185](https://lexlege.pl/ustawa-o-gospodarce-nieruchomosciami/art-185/), [lexlege — art. 186](https://lexlege.pl/ustawa-o-gospodarce-nieruchomosciami/art-186/), [lexlege — art. 186a](https://lexlege.pl/ustawa-o-gospodarce-nieruchomosciami/art-186a/), [administrator24 — zarządca po zmianie u.g.n.](https://www.administrator24.info/artykul/wspolnoty-mieszkaniowe/188519,zarzadzanie-nieruchomoscia-i-zarzadca-nieruchomosci-po-zmianie-ugn).

### S2 — Payment allocation order (feeds F3)
- KC art. 451: the debtor may indicate which debt a payment covers, but the creditor may first credit the payment against **overdue ancillary claims (interest) and overdue principal** of that debt. Absent indication: oldest due debt first. → F3's allocator needs a deterministic, documented allocation policy (interest → oldest charge → current), visible on the tenant ledger; silent ad-hoc allocation misstates arrears and interest.

### S2 — Interest on late rent
- Art. 481 § 1–2 KC: the creditor **may** (never must) demand odsetki ustawowe za opóźnienie without proving loss; rate = **NBP reference rate + 5.5 p.p.** — currently **9.25%/yr** (NBP ref 3.75%, unchanged since 5.03.2026); max = 2× that (18.5%). No compounding (art. 482 KC). Design: an **optional, per-tenancy-toggled** `InterestAccrued` charge line (taxonomy already has `interest`), computed day-by-day per rate periods — the rate is time-varying, so store rate windows, not a constant.
  Sources: [lexlege — art. 481 KC](https://lexlege.pl/kc/art-481/), [dziennikmedia — odsetki 2026](https://dziennikmedia.pl/blog/odsetki-ustawowe-2026-stawki-kalkulator/), [bewagroup — odsetki 2026](https://www.bewagroup.pl/blog/ile-wynosza-ustawowe-odsetki-w-2026).

### S2 — Retention: financial records ≥ 5 years (interacts with PII lookaside)
- Tax books and related documents must be kept until the tax obligation prescribes: **5 years counted from the end of the calendar year in which the payment deadline fell** (art. 86 § 1 + art. 70 § 1 Ordynacji podatkowej) — effectively up to ~6 calendar years; VAT invoices likewise (art. 112–112a ustawy o VAT). A fundacja's księgi rachunkowe/dowody: 5 years (art. 74 ustawy o rachunkowości); approved financial statements: 5 years from approval.
- Civil claims run longer than you'd think: periodic claims (rent) prescribe in **3 years**, other claims 6 (art. 118 KC); landlord/tenant claims re condition of the lokal — 1 year from return (art. 677 KC).
- **PII-lookaside consequence**: a tenant's right-to-be-forgotten does **not** reach data needed for tax/defence-of-claims retention (RODO art. 17 ust. 3 lit. b/e). The lookaside deletion policy needs a **financial-hold flag**: contact rows referenced by ledger documents are erasable only after `max(taxRetentionEnd, civilPrescriptionEnd)` — model as `RetentionHoldSet/Released` on the ledger side.
  Sources: [infor — jak długo przechowywać księgi](https://ksiegowosc.infor.pl/podatki/ordynacja-podatkowa/5214524,jak-dlugo-trzeba-przechowywac-ksiegi-podatkowe-i-dowody-ksiegowe.html), [GOFIN — przechowywanie dokumentacji](https://www.gofin.pl/podatki/17,2,64,196173,jak-dlugo-przechowywac-firmowa-dokumentacje-podatkowa.html).

---

## 2. Documents we must be able to produce + deadlines

| # | Document | When mandatory | Deadline | Legal basis | Model hook |
|---|---|---|---|---|---|
| 1 | **Pokwitowanie** (receipt of payment) | Whenever the paying tenant demands one (cash rent especially); refusal entitles the tenant to withhold payment or pay into court deposit | On the spot / without delay | KC art. 462 (form: § 2; cost on debtor § 3), art. 463 | `PaymentReceived` → printable receipt view |
| 2 | **Rachunek** (O.p.) | On tenant's demand, if no faktura duty applies; najem prywatny counts as działalność gospodarcza in O.p. sense | **7 days** from demand (or from performance if demanded earlier); no duty if demanded > 3 months after | Ordynacja podatkowa art. 87 § 1, 3–4 | derived doc over charge+payment lines ([rp.pl](https://www.rp.pl/podatki/art12474871-pit-rachunek-za-najem-prywatny), [lexlege art. 87](https://lexlege.pl/ordynacja-podatkowa/art-87/)) |
| 3 | **Faktura (zw.)** for exempt residential rent | On tenant's demand made within **3 months** from end of month of service/payment (landlord is a VAT podatnik — zwolniony — so ust. 3 pkt 2 applies to sprzedaż zwolnioną) | Demand in same month → by **15th of next month**; later → **15 days** from demand | VAT act art. 106b ust. 2, ust. 3 pkt 2; art. 106i ust. 6 | Q5's "create up to X" invoice generator; must cite exemption basis (art. 43 ust. 1 pkt 36) on the document ([lexlege art. 106b](https://lexlege.pl/ustawa-o-podatku-od-towarow-i-uslug/art-106b/), [podatki.biz — 3 miesiące](https://www.podatki.biz/artykuly/jak-dlugo-konsumenci-moga-zadac-wystawienia-faktury-do-paragonu_55_42217.htm)) |
| 4 | **Faktura in KSeF** | If the tenant is a **business (NIP)** and requests/receives a faktura — KSeF e-invoice obligatory from **1.04.2026** for all VAT taxpayers incl. zwolnieni; transitional: issuers ≤ 10 000 zł gross/month may stay outside KSeF until **31.12.2026**. Consumer (B2C) invoices: KSeF **not** obligatory | as in row 3 | KSeF amendment to VAT act (in force 2026) | flag on Contact: business tenant → invoice pipeline must be KSeF-capable in 2027 ([mBank — KSeF a najem](https://www.mbank.pl/artykuly/ksef-najem-prywatny/), [podatki.gov.pl — konsumenci](https://ksef.podatki.gov.pl/konsumenci-i-osoby-fizyczne/), [poradnikprzedsiebiorcy — najem w KSeF](https://poradnikprzedsiebiorcy.pl/-najem-prywatny-w-ksef-czy-nalezy-wykazywac)) |
| 5 | **Zestawienie opłat niezależnych + przyczyna podwyżki** (written) | On every increase of media advances | With/before the increase taking effect | u.o.p.l. art. 9 ust. 2 | `MediaAdvanceChangeScheduled` must emit a tenant-facing statement doc |
| 6 | **Media settlement pack** (advances vs actual, supplier invoices, meter readings, allocation key) | On settlement and on tenant challenge | settlement cadence per umowa; documentation on demand | u.o.p.l. art. 9 ust. 2 (koszty niezbędne) | `MediaSettlementPrepared` + `HandoverProtocolRecorded` evidence bundle |
| 7 | **Deposit settlement statement** (deductions itemized against protocol evidence) | Every tenancy end with a deposit | **vacateDate + 1 month** | u.o.p.l. art. 6 ust. 4 / art. 19f ust. 6 | `DepositSettlementDue` → `DepositSettled` with deduction lines |
| 8 | **Nota obciążeniowa** for tenant damage (no VAT — odszkodowanie is outside VAT); **refaktura/faktura** only where the contract frames it as a service recharge | On repair recharge (`repairRecharge` line) | with the charge | KC art. 471/675; u.o.p.l. art. 6b (tenant's maintenance duties), 6e; VAT: odszkodowanie not a supply | `RepairRechargeCharged {basis: damageNote | serviceRecharge}` ([sip.lex — nota czy faktura](https://sip.lex.pl/pytania-i-odpowiedzi/czy-obciazenie-najemcy-kosztami-napraw-do-ktorych-byl-zobowiazany-nastapi-na-621945056), [gofin — odszkodowanie: nota](https://czasopismaksiegowych.gofin.pl/poradnik-vat/archiwum-rocznikowe/303006/odszkodowanie-z-tytulu-uszkodzenia-maszyny-faktura-czy-nota-obciazeniowa)) |
| 9 | **Owner statement** (F4: balances, categorized income/pass-through/expenses incl. management fee, disbursement) | Contractual + feeds tax | monthly, before the 20th-of-month tax deadline | contract; ryczałt art. 21 | `OwnerStatementPrepared` |
| 10 | **Rent-mirror data to gmina** | Zarządca statutory duty | half-yearly per rozporządzenie | u.g.n. art. 186a | `RentMirrorReportExtracted` (projection) |
| 11 | **Monthly ryczałt report to owner's accountant** | Per engagement | by ~10th so tax can be paid by the **20th** | ryczałt act art. 21 | see §3 hand-off |

Direction of invoicing for repairs: **contractor → invoices the owner** (or manager, per contract); **owner → nota obciążeniowa to the tenant** for damage-caused cost. The tenant never gets the contractor's VAT invoice re-addressed to them.

---

## 3. Tax-regime cheat sheet + monthly hand-off to the accountant

### A. Physical person (today) — ryczałt ewidencjonowany
| Aspect | Rule |
|---|---|
| Basis | art. 2 ust. 1a, 6 ust. 1a, 12 ust. 1 pkt 4 lit. a ryczałt act; obligatory form for najem prywatny since 2023 |
| Rates | 8.5% ≤ 100 000 zł/yr; 12.5% above; spouses: joint 100k, or 200k for the one spouse taxing all (statement) |
| Tax point | **cash received** (or at disposal) |
| Base | czynsz najmu only; media/admin borne by tenant per explicit contract wording = excluded; `errorAnnulled` reversals excluded (F7) |
| Payment | monthly by the **20th** of the following month (quarterly option); to owner's mikrorachunek — **the owner pays, not us** |
| Annual | **PIT-28 filed 15.02–30.04** of the following year (note: older sources say end of February — outdated) |
| Records | no formal ewidencja for najem prywatny (art. 15 ust. 3); proof = contracts + bank receipts |
| VAT | residential rent exempt (art. 43 ust. 1 pkt 36) but **counts toward the art. 113 subjective limit — 240 000 zł from 1.01.2026** (real-estate transactions are not excluded); metered media re-invoiced also count |

**Monthly hand-off (ryczałt)** — one file per owner, by the ~10th:
1. Received-rent register, cash-basis: per receipt — date credited, tenancy ref, amount allocated to `rent` (taxable) vs `mediaAdvance`/`adminFee`/`deposit`/`repairRecharge` (non-taxable pass-through/neutral), payment reference.
2. YTD taxable revenue vs the 100k threshold + computed tax due (8.5/12.5 split) — advisory, accountant confirms.
3. Reversals/corrections this month (incl. `errorAnnulled`).
4. Copies of any faktury/rachunki issued.
5. Flags: interest received (tax treatment of odsetki — accountant's call), damage compensations received.

### B. Fundacja rodzinna (planned owner)
| Aspect | Rule |
|---|---|
| Basis | ustawa z 26.01.2023 o fundacji rodzinnej (Dz.U. 2023 poz. 326); CIT act art. 6 ust. 1 pkt 25 |
| Rental income | najem/dzierżawa/udostępnianie mienia is **permitted activity (art. 5 ust. 1 pkt 2 u.f.r.)** → rental income **CIT-exempt** while inside art. 5 |
| Exceptions | activity beyond art. 5 → **25% CIT** (art. 24r); rental of assets serving the business of the **fundator/beneficiary/related entity** → no exemption (art. 6 ust. 8); **podatek od przychodów z budynków** (art. 24b, 0.035%/month) applies above 10 mln zł building value — exemption does not cover it |
| Distributions | **15% CIT** on świadczenia to beneficiaries / hidden profits (art. 24q), payable by the 20th of the month after the benefit; beneficiaries in "grupa zero" free of PIT (art. 21 ust. 1 pkt 157 PIT) |
| Books | full **księgi rachunkowe** (ustawa o rachunkowości) + annual **CIT-8FR**; no monthly CIT advances while fully exempt |
| **2026 tightening — VETOED** | The Aug-2025 draft (long-term residential rental **only** kept exempt; short-term/commercial taxed; **36-month lock-up** on selling contributed assets at 19%; CFC) was **vetoed by the President in Nov 2025**; relegislation announced for Q2/Q3 2026 with lock-up possibly counted from assets contributed **after 31.08.2025**. **Track this; the fundacja transfer timing (hotspot #17) should be planned with counsel around it.** Sources: [prawo.pl](https://www.prawo.pl/podatki/fundacje-rodzinne-czy-beda-kompleksowe-zmiany,1537202.html), [Grant Thornton](https://grantthornton.pl/publikacja/fundacja-rodzinna-planowane-zmiany-od-2026-roku/), [Sadkowski i Wspólnicy](https://sadkowskiiwspolnicy.pl/co-zmieni-sie-w-opodatkowaniu-fundacji-rodzinnych-od-1-stycznia-2026-r/), [chudzikowski.pl](https://chudzikowski.pl/czy-dochody-fundacji-rodzinnej-z-najmu-nieruchomosci-sa-zwolnione-z-cit/), [KIS 21.05.2025, 0111-KDIB1-2.4010.92.2025.1.BD](https://www.inforlex.pl/dok/tresc,FOB0000000000006962057,Zwolnienie-dochodu-fundacji-rodzinnej-z-podatku-dochodowego-od-najmu-lokalu-mieszkalnego-na-podstawie-Ustawy-CIT-Interpretacja-indywidualna-z-dnia-21-maja-2025-r-Dyrektor-Krajowej-Informacji-Skarbowej.html) |

**Monthly hand-off (fundacja rodzinna)** — accrual world, full books:
1. Sales register: every charge/document issued (czynsz, media, notes), accrual dates + corrections.
2. Rozrachunki: per-tenant receivables ageing, per-supplier payables (media, repairs), deposit liabilities (kaucje are **liabilities**, not income).
3. Owner-split & management-fee invoices (operator's revenue) — the fee is a cost in the fundacja's books.
4. Classification flags the accountant needs to guard the exemption: any **related-party tenancy**, any **short-term/commercial letting** on the portfolio.
5. Benefit-payment notices (any świadczenie to beneficiaries → 15% CIT event, 20th-of-next-month deadline).

### C. Ordinary fundacja (if not rodzinna)
- Normal CIT payer: 9% (small) / 19%; rental income taxable **unless** the dochód is destined for and spent on the statutory purposes listed in **art. 17 ust. 1 pkt 4 CIT** (science, education, culture, charity, health…) — then exempt as long as actually so spent; full księgi rachunkowe; monthly CIT advances by the 20th; annual CIT-8 + CIT-8/O. Hand-off = same accrual pack as B, plus a **destination-of-income trail** (what rental profit funded which statutory spend). Sources: [ISP Modzelewski](https://isp-modzelewski.pl/blog/cit/zwolnienie-z-cit-dla-fundacji-przy-zakupie-nieruchomosci-art-17-ust-1-pkt-4-ustawy-o-cit/), [iwop.pl — CIT dla NGO](https://www.iwop.pl/aktualnosci/podatek-dochodowy-od-osob-prawnych-dla-ngo/).
- **The two fundacja regimes differ fundamentally** (rodzinna: exempt-by-subject with activity carve-outs, 15% on distributions; zwykła: taxable with purpose-based exemption). The Accounting design only needs regime-agnostic accrual ledgers + classification flags; the accountant does the rest.

**Regime switch mechanics:** `PropertyOwnershipChanged` → new owner entity with `taxRegime {ryczaltPIT | fundacjaRodzinnaCIT | fundacjaCIT}` effective-dated; the monthly hand-off generator picks the pack format per owner-period. Mid-month transfer = two part-month packs.

---

## 4. Traps / awareness

1. **S1 — Deposit cap table in F1 is wrong**: instytucjonalny is 6× (art. 19f ust. 5), not 3×. Fix before the ACL warn-gate is built.
2. **S1 — Contract wording gates the ryczałt media exclusion.** If a tenancy is quoted as one lump sum with no contractual czynsz/media split, the *whole* amount is the owner's taxable revenue. The optional breakdown in `TenancyReserved` should be near-mandatory in practice; warn when absent ("entire amount will enter the ryczałt base").
3. **S2 — VAT sleeper: the 240k limit.** Exempt residential rent **does count** toward art. 113's 240 000 zł (real-estate transactions aren't excluded), and metered media resold per TSUE **C-42/14** (Wojskowa Agencja Mieszkaniowa, 16.04.2015) are separate supplies at their own rates (water/sewage 8%, energy/gas 23%). A growing portfolio can silently push the owner over the limit → registration, VAT on media, JPK_V7. The ledger should track rolling-12-month owner turnover and warn. Sources: [infor — co wlicza się do limitu 2026](https://ksiegowosc.infor.pl/podatki/vat/zwolnienia/7605594,zwolnienie-podmiotowe-w-vat-w-2026-r-co-wlicza-sie-do-limitu-kiedy-podatnik-traci-prawo-do-zwolnienia-i-czy-moze-do-niego-wrocic.html), [Grant Thornton — C-42/14](https://grantthornton.pl/publikacja/rozliczanie-vat-od-mediow-wyrok-tsue-w-sprawie-c-4214/), [poradnikprzedsiebiorcy — najem a limit](https://poradnikprzedsiebiorcy.pl/-najem-prywatny-i-dzialalnosc-a-limit-zwolnienia-z-vat).
4. **S2 — Renting to a company for its employees breaks the VAT exemption** (art. 43 ust. 1 pkt 36 requires letting *na własny rachunek, wyłącznie na cele mieszkaniowe* of the tenant; interpretacja ogólna MF 8.10.2021 — letting to a firm that houses staff = 23%). Flag business tenants on residential units.
5. **S2 — KSeF creep.** Today B2C faktury stay outside KSeF and the ≤10k zł/month transitional shields small issuers until 31.12.2026 — but a single business tenant demanding an invoice from 2027 puts the invoice pipeline into KSeF (structured XML, KSeF number as the legal identifier). Build the invoice generator so the rendering (PDF for consumers) is separable from the fiscal channel.
6. **S3 — Fundacja rodzinna law is in flux** (vetoed Nov 2025, returning 2026). Two planning consequences: (a) transfer timing vs a lock-up counted from 31.08.2025; (b) if the tightening passes as drafted, only *direct long-term residential* letting stays exempt — any structure where an operator interposes as tenant/subletter (fundacja → operator → tenants) would forfeit it. Keep the zarządca an **agent**, not an intermediary tenant.
7. **S3 — No trust-account statute ≠ no exposure.** Commingling owner money, deposits and the operator's management fee in one account is legal but turns every dispute into an evidence problem and is an OC-insurance risk. F4's "whose-money" ledger + separate deposit account (Q4) is the de-facto compliance mechanism; the ledger, not the bank, is the source of truth for segregation.
8. **S3 — Interest is a right, not a duty** (art. 481 KC): charging is optional, rate floats (9.25% now; store rate windows), max 2×, no anatocyzm. Auto-accrual should be a per-owner/per-tenancy policy toggle with explicit `InterestAccrued`/`InterestWaived` events — waiving is a business norm worth recording, and allocation (art. 451 KC: interest before principal) must be deterministic.
9. **S3 — Damage recharge documents:** odszkodowanie → **nota obciążeniowa** (no VAT); only contractual service-recharges get a faktura. Deductions from deposit must trace to `HandoverProtocolRecorded` (move-in vs move-out) or they fail in court — F1 already requires this; extend the same evidence rule to mid-tenancy `repairRecharge` lines.
10. **S3 — Retention beats erasure.** PII-lookaside deletion must check the financial-hold (≥5 years tax + civil prescription); conversely, hotspot #15 (dead leads) is *not* blocked by any of this — leads with no financial trail can be erased freely.
11. **S3 — 3-period arrears (F6)** ties to art. 11 ust. 2 pkt 2 u.o.p.l. (termination needs arrears of 3 full periods **plus** a month's written warning with an extra month to pay). `ArrearsReached3Periods` should therefore be computed on **full charge periods unpaid**, not on amounts, and the future termination process must model the warning letter step.

---
*Prepared for the Accounting event-storming session; not legal advice. Statutes cited: u.o.p.l. (t.j. Dz.U. 2023 poz. 725 ze zm.), u.g.n. (t.j. Dz.U. 2024 poz. 1145), ustawa o VAT (t.j. Dz.U. 2025 poz. 775), ryczałt act (t.j. Dz.U. 2024 poz. 776), Ordynacja podatkowa (t.j.), KC (t.j. Dz.U. 2025 poz. 1071), ustawa o fundacji rodzinnej (Dz.U. 2023 poz. 326), ustawa o CIT (t.j.).*
