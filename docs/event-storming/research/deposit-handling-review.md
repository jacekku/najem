# Tenant Deposit (Kaucja) — Deep-Dive Review

**Date:** 2026-08-03 · **For:** Accounting session (resolves PM hotspot #4 / constraint F1)
**Builds on:** `polish-rent-law-review.md` (caps, 1-month deadline, valorization basics — not repeated here), `property-management-domain-model.md` v1.1 (`HandoverProtocolRecorded`, `DepositSettlementDue`, `vacateDate` exist).

---

## 0. Corrections & sharpenings to the baseline legal review

1. **🟥 The instytucjonalny cap is 6×, not 3×.** Art. 19f **ust. 5** u.o.p.l. (current text): *"Kaucja nie może przekraczać sześciokrotności miesięcznego czynszu za dany lokal, obliczonego według stawki czynszu obowiązującej w dniu zawarcia umowy najmu instytucjonalnego lokalu."* The 3× figure in `polish-rent-law-review.md` §0 is the pre-2019 text (raised by the 2019 KZN amendment). Also note the cap sits in **ust. 5**, not ust. 4. → ACL cap table: **zwykły 12× (art. 6 ust. 1) / okazjonalny 6× (art. 19a ust. 4) / instytucjonalny 6× (art. 19f ust. 5)**. Cap is always computed on the **rent rate at signing**. Sources: [art. 19f (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19f/), [art. 19a (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19a/).
2. **Instytucjonalny allows mid-tenancy draw-down.** Art. 19f **ust. 7**: if the tenant misses a payment during the tenancy, *"właściciel może zaspokoić należną wierzytelność z kaucji"*. No statutory equivalent for zwykły/okazjonalny (there the deposit secures claims **as of the vacate day** — art. 6 ust. 1, art. 19a ust. 4). This is a whole extra lifecycle branch (draw-down + replenishment) that only exists for the fundacja-era tenancies.
3. **Valorization applies to all three regimes.** Art. 6 ust. 3 is explicitly on the applicable-provisions lists: **art. 19e** (okazjonalny) and **art. 19j** (instytucjonalny) both include *"art. 6 ust. 3"*. Popular guidance claiming okazjonalny deposits return nominal is contradicted by the statute text — flagged as lawyer question Q1, but the model should support valorization for every `legalForm`. Sources: [art. 19e (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19e/), [art. 19j (lexlege)](https://lexlege.pl/ochr-pr-lok/art-19j/).

---

## 1. Valorization mechanics (art. 6 ust. 3) — the deposit is a formula, not an amount

**Statute:** *"Zwrot zwaloryzowanej kaucji następuje w kwocie równej iloczynowi kwoty miesięcznego czynszu obowiązującego w dniu zwrotu kaucji i krotności czynszu przyjętej przy pobieraniu kaucji, jednak w kwocie nie niższej niż kaucja pobrana."* ([arslege](https://arslege.pl/kaucja-zabezpieczajaca/k23/a3817/), worked examples: [BIP Kraków](https://www.bip.krakow.pl/?dok_id=28861))

```
refundBase = krotność_at_signing × czynsz_in_force_on_return_day
refund     = max(refundBase, nominalDeposit) − lawful deductions
```

Practical edge cases for the model:

| Edge case | Resolution | Basis |
|---|---|---|
| Rent changed mid-tenancy (increase) | **Rent in force on the return day** counts — every `RentChangeScheduled` that lands changes the future refund. After the tenancy has ended, the reference is the last rent in force under the agreement (no rent "obowiązuje" post-termination — lawyer Q2). | art. 6 ust. 3 literal text |
| Rent **decreased** mid-tenancy | Floor: never below nominal (*"nie niższej niż kaucja pobrana"*) | art. 6 ust. 3 in fine |
| Deposit was not a round multiple (e.g. 3 000 zł vs 2 500 zł rent) | `krotność` = deposit ÷ rent-at-signing = **1.2 — store the multiplier as a decimal**, derived at charge time, immutable thereafter | statute says "krotność przyjęta przy pobieraniu" |
| Rent is one component of a quoted monthly total (`{rent, adminFee, mediaAdvance}`) | Valorization base is **czynsz only** — opłaty niezależne are not czynsz (art. 9 ust. 5-6 u.o.p.l. distinction). This makes the F2 line-item split load-bearing for the deposit formula, not just for tax. | art. 6 ust. 3 ("czynsz") |
| Partial months / proration | Irrelevant to the formula — it references the **monthly** rent rate, not amounts actually billed | statute |
| Indefinite tenancy | No difference; valorization triggers on return, not on any term | statute |
| Communal-housing income-verification rent hikes producing windfall refunds | Known legislative gap; a pending amendment caps valorized refunds for the **public** housing stock only — private najem unaffected but shows the formula is applied literally by courts | [Sejm interpelacja](https://orka2.sejm.gov.pl/IZ6.nsf/main/6E577DBF), [gov.pl draft](https://www.gov.pl/web/premier/projekt-ustawy-o-zmianie-ustawy-o-ochronie-praw-lokatorow-mieszkaniowym-zasobie-gminy-i-o-zmianie-kodeksu-cywilnego-oraz-ustawy-o-dodatkach-mieszkaniowych) |
| Pre-1994 / 1994-2001 deposits | Separate regime (art. 36 u.o.p.l., SN case law on old kaucje) — **out of scope**, portfolio has none | art. 36 u.o.p.l. |

**Key SN authority on the deposit's nature:** uchwała SN z 26.09.2002, **III CZP 58/02** — from the moment of payment, tenant and landlord are bound by a **separate obligation relationship** (distinct from the lease) under which the tenant holds a money claim (wierzytelność) for return. The cash itself becomes the landlord's property (fungible money); the tenant is an **unsecured creditor**. This single holding drives both the money-handling analysis (§4) and the ownership-transfer analysis (§5). Source: [standardyprawa.pl](https://standardyprawa.pl/standardy/12859), [arslege art. 678](https://arslege.pl/zbycie-przedmiotu-najmu/k9/a5683/).

**Clock start (practice + case law):** the 1-month return period (art. 6 ust. 4 / 19a ust. 5 / 19f ust. 6) runs from **opróżnienie lokalu** — in practice treated as physical vacation **confirmed by the move-out protocol**; delaying the protocol shifts the deadline at the landlord's risk ([kluczo — zwrot kaucji](https://kluczo.pl/blog/zwrot-kaucji-termin)). Model: clock = `max(vacateDate, HandoverProtocolRecorded(moveOut).date)` — matches the existing `DepositSettlementDue` but sharpens its deadline input.

---

## 2. Deductions — what survives in court

**What may lawfully be deducted:**
- **Zaległości czynszowe + opłaty** existing on the vacate day (art. 6 ust. 1: *"należności z tytułu najmu przysługujące w dniu opróżnienia"*) — yes, this is the primary use.
- **Odszkodowanie za bezumowne korzystanie** (holdover, art. 18 ust. 1-2 u.o.p.l.) — arguably a "należność z tytułu najmu"; lawyer Q10.
- **Damage beyond normal wear**: tenant must return the unit per art. 6e u.o.p.l. (renovation obligations net of **zużycie naturalne**) and KC art. 675 (return in non-deteriorated state; normal wear excluded). Deductible: broken fixtures, wall holes, destroyed appliances, cleaning beyond normal. **Not deductible**: faded paint after years, minor scratches, worn carpets, burnt-out bulbs — the ordinary aging of the unit ([kluczo — normalne zużycie](https://kluczo.pl/blog/normalne-zuzycie-kaucja), [wilsons.pl](https://wilsons.pl/zwrot-kaucji-a-zniszczenia-w-mieszkaniu-co-prawo-pozwala-potracic-wlascicielowi)).
- **Okazjonalny/instytucjonalny additionally:** documented **koszty egzekucji obowiązku opróżnienia lokalu** (art. 19a ust. 4, art. 19f ust. 4).

**Documentation standard courts actually apply** (appellate practice, e.g. SO Wrocław [II Ca 1263/14](https://orzeczenia.wroclaw.so.gov.pl/content/$N/155025000001003_II_Ca_001263_2014_Uz_2014-12-29_001); SO Poznań II Ca 941/20):
1. **Burden of proof is on the landlord** (KC art. 6): he must prove the unit came back worse than normal wear relative to a documented starting state.
2. **No move-in protocol ⇒ landlord almost always loses** — you cannot prove deterioration without a baseline. The art. 6c protokół is literally "the basis for settlements at return".
3. Expected evidence bundle per deduction: **move-in protocol + move-out protocol + photos (before/after) + kosztorys or repair invoice**. Estimated "round numbers" without invoices get struck.
4. **Tenant approval of deductions is NOT required** — the settlement is the landlord's unilateral act (set-off within the statutory settlement) — but an **itemized written settlement statement** must accompany the partial refund, and the tenant can litigate any line ([marcinrusinek.pl](https://marcinrusinek.pl/blog/potracenie-z-kaucji-za-sprzatanie-mieszkania-po-najemcy-kiedy-jest-mozliwe-i-jak-sie-odwolac/)).
5. **Deadline interplay (important, not in baseline):** the landlord's damage claims against the tenant **prescribe 1 year from the return of the unit** (KC **art. 677**). Deduct-or-sue within a year; the tenant's deposit-return claim prescribes on general terms — **6 years** (KC art. 118) ([jakzrozumiecprawnika.pl](https://jakzrozumiecprawnika.pl/przedawnienie-roszczen-z-najmu/)).

**Model consequence:** every `DeductionClaimed` must carry `evidenceRefs` and the ACL should warn when a damage-type deduction lacks (a) a move-in `HandoverProtocolRecorded`, (b) a move-out one, or (c) an invoice/kosztorys document. This is the compliance-seat pattern already established in the context map.

---

## 3. The deposit lifecycle — state machine with citations

```
                         ┌──────────────────────────────────────────────────────────┐
                         │  (instytucjonalny only, art. 19f ust. 7)                 │
                         │  HELD ──DrawnDown──▶ PARTIALLY_APPLIED ──Replenished──┐  │
                         │    ▲                       │ (contract clause)        │  │
                         │    └───────────────────────┴──────────────────────────┘  │
                         ▼
NONE ──charge──▶ CHARGED ──payment──▶ HELD ──vacate+protocol──▶ SETTLEMENT_DUE ──settle──▶ SETTLED
        │           │                  │                            │                        ├─ RETURNED (full, valorized)
        │           │                  │                            │                        ├─ PARTIALLY_WITHHELD
        │           └─ UNPAID/WAIVED   └─ OBLIGATION_TRANSFERRED    └─ OVERDUE ──▶ DISPUTED  └─ FORFEITED (tax event)
        └─ (deposit optional — "może być uzależnione", art. 6 ust. 1)
```

| Transition | Trigger / rule | Legal basis |
|---|---|---|
| NONE → CHARGED | Agreement signed; amount ≤ cap per `legalForm` (12×/6×/6× of signing-day rent); multiplier snapshot stored | art. 6 ust. 1-2; 19a ust. 4; 19f ust. 5 |
| CHARGED → HELD | Payment received (may be partial → track balance); handover may be conditioned on payment | art. 6 ust. 1 ("może być uzależnione") |
| HELD → PARTIALLY_APPLIED | Instytucjonalny: unpaid rent satisfied from deposit mid-tenancy; zwykły/okazjonalny only if the contract says so (lawyer Q5) | art. 19f ust. 7 |
| PARTIALLY_APPLIED → HELD | Tenant replenishes (contractual duty, not statutory) | contract clause |
| HELD → SETTLEMENT_DUE | `vacateDate` + move-out `HandoverProtocolRecorded`; deadline = clock start + 1 month | art. 6 ust. 4; 19a ust. 5; 19f ust. 6; art. 6c (protocol = settlement basis) |
| SETTLEMENT_DUE → RETURNED | Refund = max(multiplier × rent-at-return, nominal) − deductions; itemized statement | art. 6 ust. 3 |
| SETTLEMENT_DUE → PARTIALLY_WITHHELD / FORFEITED | Documented deductions (protocol + photos + invoices); burden of proof on landlord | art. 6 ust. 1, 6e; KC 675, KC 6; II Ca 1263/14 |
| SETTLEMENT_DUE → OVERDUE | Deadline passed, money not sent; statutory interest accrues (KC 481 — 11.25%/yr in 2026); tenant path: demand → EPU payment order | KC art. 481; [kluczo](https://kluczo.pl/blog/zwrot-kaucji-termin), [RPMS](https://rpms.pl/windykacja-niezwroconej-kaucji-po-zakonczonym-najmie/) |
| HELD → OBLIGATION_TRANSFERRED | Ownership transfer + przejęcie długu with tenant's consent (§5 — NOT automatic) | KC 519/522; SN III CZP 58/02 |
| FORFEITED/applied → tax event | Retained amount becomes **przychód z najmu** at the retention moment (ryczałt-taxable, cash basis) | NSA line + KIS 0114-KDIP3-2.4011.208.2023.2.MJ ([interia](https://biznes.interia.pl/podatki/news-wynajem-mieszkania-czy-od-kaucji-trzeba-placic-podatek-wazny,nId,8000275), [podatki.biz](https://www.podatki.biz/artykuly/najem-prywatny-na-ryczalcie-zatrzymana-kaucja-jest-przychodem_62_53082.htm)) |
| Death of tenant (special) | Art. 691 KC successor (household member) → tenancy + deposit continue unchanged. No successor → tenancy expires; the **return claim is inheritable** (ordinary wierzytelność) → pay documented heirs; heirs unknown → złożenie do depozytu sądowego (lawyer Q6) | KC 691; KC 922; [lexlege KC 691](https://lexlege.pl/kc/art-691/) |

**"Deposit as last month's rent":** the tenant unilaterally stopping payment and pointing at the deposit is **unlawful** — the deposit may be applied only at settlement (zwykły/okazjonalny), and arrears + interest accrue normally; only a mutual agreement can convert it ([rankomat](https://rankomat.pl/nieruchomosci/kaucja-w-umowie-najmu-mieszkania), [forumprawne](https://forumprawne.org/watek/kaucja-jako-ostatni-czynsz-perspektywa-najemcy.875075/)). Model: it needs **no special state** — it shows up as ordinary last-month arrears that the settlement nets out; but the arrears board should recognize the pattern (last month + deposit held ≥ arrears) and suggest settlement rather than escalation.

---

## 4. Money handling

**Whose money is it?** Per SN III CZP 58/02: the cash is the **owner's property** the moment it's paid; the tenant holds only a personal claim. Poland has **no statutory escrow/trust scheme** for residential deposits (no UK-style DPS, no US state trust-account mandate). A separate account is therefore **best practice, not law** — but for a zarządca handling many owners' money it is close to mandatory hygiene (u.g.n. professional-care standard, art. 184b i n.; and it is the only way to make "owner insolvent" a survivable event for the manager's reputation).

**Recommended account structure (mirrors US trust-accounting practice and fits F4):**
1. **One dedicated deposit bank account per owner entity** (physical person now; fundacja gets its own on transfer) — never commingled with the rent-collection account. Matches the session note "deposits likely have a separate account" and the multiple-bank-sources lifecycle (Q4 of the accounting session).
2. In the ledger: **liability account "Kaucje otrzymane" (zobowiązania), never income**, with a **per-tenancy subledger**. Polish bookkeeping treats received deposits exactly this way ([GOFIN](https://gofin.pl/rachunkowosc/17,1,85,220205,kaucja-z-tytulu-najmu-w-ksiegach-rachunkowych-najemcy.html), [poradnikksiegowego](http://www.poradnikksiegowego.pl/artykul,91,3414,kaucje-z-tytulu-najmu-w-ewidencji-ksiegowej.html)).
3. **Three-way reconciliation invariant** (the control comparable systems treat as core): `deposit bank balance == Σ liability subledger == Σ HELD deposits per active tenancy` — a projection with a red flag, refreshed on daily statement ingestion.
4. **Valorization headroom warning:** since the refund can exceed the nominal amount held (multiplier × risen rent), the account can be structurally short. Read model: `Σ current valorized refund obligations` vs bank balance; prompt the manager to top up from owner funds before `DepositSettlementDue`.

**Interest on the deposit account:** accrues to the **account holder (owner)** — no Polish provision assigns it to the tenant in private najem; the tenant's only statutory upside is valorization. Bank interest is the owner's capital income (Belka tax withheld at source). A contract may promise "kaucja z odsetkami" — then it's contractual. (Lawyer Q7 for the fundacja's accounting treatment.)

**Tax treatment of the deposit itself:** receipt is **tax-neutral** (returnable → not przychód); przychód arises **only at the moment of lawful retention/application**, classified as rental income (ryczałt-taxable at 8.5/12.5%, cash basis — so it feeds the F5 monthly received-rent report in the month of retention). Deposit applied to rent arrears = rent received that month. Sources: [GazetaPrawna](https://www.gazetaprawna.pl/podatki/artykuly/10755950,czy-kaucja-w-umowie-najmu-podlega-opodatkowaniu.html), [poradnikprzedsiebiorcy](https://poradnikprzedsiebiorcy.pl/-zatrzymana-kaucja-jak-ja-poprawnie-ujac-w-ewidencji), [prawo.pl](https://www.prawo.pl/podatki/czy-jest-podatek-od-zwrotu-kaucji-za-wynajem,534481.html).

---

## 5. The ownership-transfer scenario (fundacja takes the building)

This is the sharpest legal trap in the whole deposit domain, and it is **counter-intuitive**:

- **KC art. 678 § 1** transfers the *tenancy* to the acquirer — but per **uchwała SN z 26.09.2002, III CZP 58/02** (and recent application, SR Warszawa-Śródmieście 20.05.2022, **VI C 837/21**), the deposit-return obligation **does NOT pass by law**: it belongs to the *separate* obligation relationship created by the deposit payment, and art. 678 transforms only the lease's parties, not pre-transfer collateral claims. **The physical-person owner remains the tenant's debtor for every deposit taken before the transfer** — even after the fundacja owns the building. Sources: [arslege art. 678](https://arslege.pl/zbycie-przedmiotu-najmu/k9/a5683/), [rp.pl](https://www.rp.pl/regulacje-prawne-i-przepisy/art40515281-sprzedaz-nieruchomosci-a-kaucje-i-zabezpieczenia), [infor](https://www.infor.pl/prawo/umowy/najem-i-dzierzawy/7585173,czy-mozna-sprzedac-mieszkanie-z-najemca-zgodnie-z-prawem-co-z-rozliczeniem-umowy-najmu-po-sprzedazy-mieszkania.html), [standardyprawa](https://standardyprawa.pl/standardy/12859).
- Making the fundacja the debtor requires **przejęcie długu (KC art. 519)** — which needs the **tenant's (creditor's) consent**, and the transfer agreement must be **in writing under pain of nullity (KC art. 522)**. Practice: a tripartite annex per tenancy (owner → fundacja debt assumption + tenant consent) executed alongside moving the cash between deposit accounts.
- **Failure branch:** a tenant who refuses (or is unreachable) leaves the old owner personally liable while the fundacja holds the building — the system must be able to represent a **mixed portfolio state** during migration.
- **Re-signing as instytucjonalny** (the plan for new tenancies): if an existing tenancy is re-signed rather than continued, the old deposit must be **settled (valorized!) or rolled over by agreement** into the new instytucjonalny deposit — new 6× cap check against the new rent, and the new deposit gains the art. 19f ust. 7 draw-down branch.

**Model:** `PropertyOwnershipChanged` must trigger a **Deposit Migration Process** — per HELD deposit: `TransferDepositObligation` (requires `consentDoc` + cash-movement confirmation) → `DepositObligationTransferred {fromOwner, toOwner, consentDoc, cashMovedRef}`; unconsented deposits stay flagged `legacy-liability: previous owner` on the owner statement of the *old* owner. Rollovers: `DepositRolledOver {oldTenancyId, newTenancyId, settlementDelta}`.

---

## 6. Proposed events / commands / policies (for the Tenancy Accounting + Accounting contexts)

**Aggregate: `Deposit`** (one per tenancy; keyed `tenancyId`; owns the state machine of §3). Charge-line duplication avoided: the F2 `deposit` line-item on the tenant ledger references this aggregate.

**Commands → Events:**

| Command | Event | Payload highlights |
|---|---|---|
| ChargeDeposit | **DepositCharged** | `nominalAmount, rentAtSigning, multiplier (decimal, derived, immutable), legalForm, capCheckResult` |
| RecordDepositPayment | **DepositReceived** | `amount, date, paymentRef, balanceOutstanding` (partials allowed) |
| DrawDownDeposit | **DepositDrawnDown** | `amount, appliedToChargeIds[], legalBasis: art19f7\|contractClause` — ACL warns if zwykły/okazjonalny without clause |
| — | **DepositReplenished** | `amount` (restores HELD) |
| — (policy) | **DepositSettlementDue** *(exists, v1.1)* | deadline = `max(vacateDate, moveOutProtocolDate) + 1 month` |
| ComputeValorization (auto) | **DepositValorizationComputed** | `rentAtReturn, multiplier, valorizedAmount, floorApplied: bool` |
| ClaimDeduction | **DeductionClaimed** | `type: unpaidRent\|holdoverCompensation\|damageBeyondWear\|executionCosts\|mediaTrueUp, amount, evidenceRefs {moveInProtocol, moveOutProtocol, photos[], invoiceOrKosztorys}` — ACL warn on missing evidence |
| PrepareDepositSettlement | **DepositSettlementPrepared** | itemized statement doc (generated), `refundAmount = valorized − Σdeductions` |
| ReturnDeposit | **DepositReturned** | `amount, date, bankRef` — full or partial (partial ⇒ + **DepositPartiallyWithheld** `{withheldAmount, deductionIds[]}`) |
| ForfeitDeposit | **DepositForfeited** | `amount, reason` → policy: **tax event** — add to owner's ryczałt received-rent base for that month (F5) |
| TransferDepositObligation | **DepositObligationTransferred** | `fromOwner, toOwner, consentDoc, cashMovedRef` (§5) |
| RollOverDeposit | **DepositRolledOver** | `newTenancyId, settlementDelta, newCapCheck` |
| — | **DepositReturnOverdue** | fired at deadline; escalating prompts; surfaces accruing KC-481 interest estimate |
| RecordDepositDispute | **DepositDisputed** | `contestedAmount, courtRef?` — freezes FORFEITED accounting finality |

**Policies:**
1. `TenancyActivated` → ChargeDeposit (already in v1.1; now with multiplier snapshot + cap warn per corrected table §0).
2. `RentChangeScheduled` landed → recompute projected valorized refund (read model only — no event needed; the formula replays from `DepositCharged.multiplier` + rent history).
3. `HandoverProtocolRecorded(moveOut)` ∧ `vacateDate` set → emit `DepositSettlementDue`, auto-run ComputeValorization, prompt manager with pre-filled settlement (deductions from arrears balance + protocol deltas).
4. Deadline − 7 days ∧ not settled → warning; deadline passed → `DepositReturnOverdue` (red, litigation-risk flag).
5. `DepositForfeited` / `DepositDrawnDown` → Tenancy Accounting posts the retained amount as rental income (cash basis, that month) + reduces liability subledger.
6. `PropertyOwnershipChanged` → start Deposit Migration Process (§5) over all HELD deposits of that property.
7. Daily statement ingestion → three-way reconciliation projection (§4.3) + valorization-headroom check (§4.4).

**Read models:** Deposit Register (per owner: tenancy, nominal, multiplier, current valorized refund, state, deadline), Deposit Account Reconciliation (bank vs liability vs register), Legal-clock entries (deadline feed into the already-planned statutory-deadlines Timeline lane).

**How comparable systems do it (validates the shape):** US/UK property-management stacks uniformly model deposits as a **liability account in a segregated trust/escrow bank account with per-tenant subledgers and period-end three-way reconciliation**, states ≈ held / partially released / released / forfeited ([Baselane](https://www.baselane.com/resources/security-deposit-accounting), [RentPost trust-accounting guide](https://rentpost.com/resources/article/trust-accounting-for-property-managers/), [Rentec Direct separate deposit ledger](https://help.rentecdirect.com/article/581-tenant-security-deposits), [Buildium lease ledger](https://www.buildium.com/dictionary/lease-ledger/)). Polish tools are thinner: [Rentumi](https://rentumi.pl/artykuly/kaucja-w-umowie-najmu/) stores the kaucja as a contract parameter + payment; [simpl.rent](https://simpl.rent/wlasciciel/) focuses on deposit *payments* and a deposit-free guarantee product. **None model valorization or the settlement evidence chain — both are differentiators NAJEM gets almost free from events it already has** (`RentChangeScheduled` history + `HandoverProtocolRecorded`).

---

## 7. Open questions for a Polish lawyer

1. **Valorization for okazjonalny/instytucjonalny:** art. 19e/19j both list art. 6 ust. 3, yet market practice returns nominal — confirm the statutory reading and whether a contract can exclude valorization (art. 6 ust. 3 semi-imperative?).
2. **"Czynsz obowiązujący w dniu zwrotu"** after the tenancy ended: last contractual rent, or something else? And when rent is a component of a bundled monthly total — confirm valorization base excludes adminFee/mediaAdvance.
3. Fractional multiplier (deposit ≠ round multiple of rent) — is the derived-decimal reading of "krotność" safe?
4. **Fundacja migration:** confirm the trójstronne porozumienie / przejęcie długu (KC 519/522) construction per tenancy; what if a tenant refuses consent — can cash still move, and how do we paper the old owner's residual liability? Does aport/darowizna vs sale change anything under art. 678?
5. Mid-tenancy draw-down for **zwykły** via contract clause — enforceable? Replenishment clauses ("uzupełnienie kaucji w 14 dni pod rygorem wypowiedzenia") — valid under u.o.p.l.?
6. **Death of tenant without an art. 691 successor:** to whom and when do we return (heirs without stwierdzenie nabycia spadku? złożenie do depozytu sądowego, KC 467?) — does the 1-month clock even start, and from what event?
7. Interest earned on the segregated deposit account — cleanly the owner's? Any obligation to account for it to tenants under any regime; fundacja (rodzinna?) tax treatment of that interest.
8. May the **zarządca hold deposits in the manager's own account** (on behalf of owners) instead of per-owner accounts — u.g.n./AML/tax exposure, and whose insolvency risk is it then?
9. Settlement mechanics: is a landlord's unilateral itemized settlement + partial refund safe practice, or should we obtain the tenant's signature on the settlement (and does signing waive the tenant's claims)?
10. Is **odszkodowanie za bezumowne korzystanie** (art. 18) deductible from the deposit as a "należność z tytułu najmu... w dniu opróżnienia" for zwykły, or only where 19a ust. 4/19f ust. 4 name execution costs?
11. Landlord's 1-year prescription (KC 677) vs the settlement: if a defect is discovered after the deposit was returned, can we still claim — and should the system warn "settle within 12 months of return" on late-found damage?

---

**Cross-references:** state machine → F1; deduction line-items → F2 taxonomy (`deposit` charges); retained-deposit tax events → F5 ryczałt report; reconciliation invariant → F3/Q3 daily statement ingestion; migration process → hotspot #17 (fundacja transfer timing).
