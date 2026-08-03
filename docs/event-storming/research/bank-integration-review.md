# Bank Integration Review — BNP Paribas Polska daily statement ingestion

**Date:** 2026-08-03 (web research current as of this date)
**Context:** NAJEM must ingest the owner's BNP Paribas Bank Polska statements daily, auto-reconcile rent by transfer title (`paymentReference`), and show paid/unpaid. Multiple bank sources with lifecycle (fundacja transfer → new account; separate deposit account). See `../session-2026-08-03-accounting.md` Q3/Q4.

---

## 1. Route A — Direct PSD2 / open banking (become a TPP ourselves)

### PolishAPI status
- PolishAPI is the Polish Bank Association (ZBP) standard implementing PSD2; still the national standard in 2026, no successor. Current spec line: v2.1 (Berlin Group-derived with Polish adaptations); v3.0 work tracks ISO 20022 migration. Coexists with Berlin Group NextGenPSD2 — some banks expose one, some both.
- BNP Paribas Bank Polska exposes a PSD2 API via its Polish developer portal **https://gopsd2.bnpparibas.pl/pl/** (note: the *group* CIB portal uses STET — that is the French API, not the Polish one). AIS scope: accounts, balances, transaction history. Sandbox access for AIS requires proof of a KNF (or other EU NCA) permit sent to open-banking@bnpparibas.pl — i.e. even the sandbox is gated on registration.

### What being an AISP takes (Poland)
- **Small registered AISP (rejestr AISP), not a full license**: AIS-only activity requires *entry in the KNF register* of payment service providers (registered activity, no zezwolenie/authorization needed). KNF must register within **3 months** of a *complete* application; in practice completeness back-and-forth stretches this to 6–12 months.
- Application contents: applicant + management data, org structure, **3-year business program & financial plan**, description of AIS service, professional indemnity insurance (or comparable guarantee) — an ongoing annual cost. No minimum capital for AIS-only.
- **eIDAS certificates**: QWAC (transport) + QSEAL (sealing) from a QTSP, issued only *after* the KNF register entry exists. Order of magnitude ~€350–700/yr each + renewal admin; from 15 March 2026 CAB-Forum shortens max validity, so renewals become more frequent.
- Ongoing: KNF reporting obligations, incident reporting, per-bank onboarding quirks (each Polish bank's PolishAPI dialect differs), maintaining fallback/contingency mechanisms. "Code inspection" in the formal audit sense is not required for AIS-only registration, but KNF scrutinizes the business program and security description, and banks individually verify your certificates.
- **Consent/SCA**: since the EBA RTS amendment (applied 25 July 2023), AIS re-authentication is required at least every **180 days** (was 90). The *owner* must redo SCA in BNP's app/web twice a year per connected account. Within a consent window, background access without the user present is capped at **4 calls/day per account** (RTS 2018/389 art. 36(5)) — enough for daily ingestion.

**Verdict:** feasible but wildly disproportionate for one team managing a handful of owner accounts: ~6–12 months to first statement, insurance + certificates + compliance overhead forever, and you still get the same 180-day re-consent pain as via an aggregator.

## 2. Route B — Aggregator (ride someone else's AISP license)

| Provider | BNP PL coverage | Pricing model | API shape | Consent renewal UX | Regulatory burden |
|---|---|---|---|---|---|
| **Kontomatik** (PL/LT, CEE specialist) | **Yes — BNP Paribas (GOonline) listed explicitly**, plus all major PL banks; also PDF-statement parsing for PL banks | Quote-based (per-session/volume); no public price list | Embeddable widget + REST; transactions endpoint, labeling (60+ labels), income analysis; PSD2 AIS modes | Widget-driven re-auth; 180-day cycle | Kontomatik is the licensed AISP; you contract as its client |
| **Enable Banking** (FI) | Yes — 2,700+ banks in 30 EU countries incl. PL business + personal accounts | Per connected account/month, volume-based with monthly minimum; quote tool; self-serve sandbox + "restricted production" (whitelist own accounts) free to start | Clean JWT REST, native SDKs; transactions endpoint; you schedule fetches (respect bank's 4x/day) | Redirect-based re-auth every 180 days | Either your license **or** their AISP partner setup — ask for the partner option |
| **Tink** (Visa) | PL is a supported market; BNP Paribas is a Tink group partner (coverage of BNP PL specifically: confirm in coverage list) | ~€0.50/user/month for Transactions (Standard plan, third-party reported); enterprise minimums typically high for small clients | Mature REST, webhooks, continuous access | Tink Link handles re-consent | Tink is the AISP |
| **Salt Edge** | PL covered (1,500+ institutions; BNP PL in coverage list — Connexis/corporate entries visible, verify retail/SME GOonline) | Tiered/quote; startup plans exist but minimums reported painful for micro-scale | REST + webhooks, categorization | Salt Edge Connect widget | Salt Edge AISP (or your license) |
| **GoCardless Bank Account Data** (ex-Nordigen) | Had BNP PL | Was the free option; **closed to new signups and being wound down (mid-2025)** | — | — | **Dead end — do not build on it** |
| **Plaid EU** | PL coverage thin/retail-oriented | Enterprise pricing | — | — | Not a PL-first choice |
| **Autopay (ex-Blue Media)** | Yes — KNF-licensed AIS/PIS since 2019; actually *provides* BNP PL's own open-banking features | B2B quote; oriented at banks/e-commerce, AIS mainly for identity/income verification | REST; verification-flavored | — | Autopay AISP; product fit for statement-feed use case is weak |

Notes for all aggregators: they inherit the same PSD2 constraints — **180-day owner re-consent** and **4 background refreshes/day/account**. Daily ingestion fits comfortably; the real UX cost is the twice-yearly re-auth by a non-technical owner (mitigate with email/SMS nudge + a one-click reconnect link → `ConsentRenewalRequested`).

**Shortlist:** Kontomatik (CEE-native, explicit BNP GOonline coverage, PDF-parsing fallback in the same contract) and Enable Banking (self-serve start, transparent per-account pricing, restricted production lets you connect *your own client's account* before signing anything). Tink/Salt Edge viable but enterprise minimums likely dwarf a few accounts.

## 3. Route C — File-based fallback (e-banking exports)

- **GOonline Biznes** (BNP PL business e-banking, successor to BiznesPl@net) exports statements as **PDF and MT940** from My Finance → Statements, plus configurable export templates (CSV/XML/custom); bank-predefined and user-defined templates. camt.053 not confirmed for SME-tier — MT940 is the safe bet.
- **Automated delivery**: **BNP Paribas Connect / GOconnect Biznes** = host-to-host ERP integration (XML over web-services, two-way SSL) — corporate product, needs a bank agreement; realistic for later, not MVP. No plain SFTP drop documented for SME accounts. Popular Polish middleware (emSzmal, Fakturownia importers) proves the MT940/CSV files are stable and parseable.
- **Parsing effort**: MT940 is a solved problem — line-oriented SWIFT tags (`:61:` entry, `:86:` details incl. transfer title, counterparty, account); mind BNP's `:86:` sub-field dialect and Polish encoding (Windows-1250/UTF-8). A parser + tests ≈ 2–4 dev-days. camt.053 (ISO 20022 XML) is even easier if it ever appears. The transfer title needed for `paymentReference` reconciliation is present in `:86:`.
- Cost: zero. Friction: a human downloads a file daily/weekly (or the manager does it during arrears review). No consent lifecycle at all — but manual toil and gap risk.

## 4. Recommendation (MVP, small team)

| Rank | Route | Time to first statement | Monthly cost | Owner consent friction | Switching cost later |
|---|---|---|---|---|---|
| 1 (start) | **C: manual MT940/CSV upload** | Days (parser only) | 0 zł | None (owner grants manager e-banking view access once) | Low — same `StatementFetched→TransactionIngested` pipeline stays |
| 2 (next) | **B: aggregator (Enable Banking or Kontomatik)** | 2–6 weeks (contract + integration) | tens–low hundreds zł for a few accounts | SCA redirect at connect + every 180 days | Medium — swap adapter, keep domain events |
| 3 (avoid for now) | **A: own AISP registration** | 6–12 months | insurance + certs + compliance | Same 180-day SCA anyway | High sunk cost; only worth it at many-owners scale |

**Sequence:** build the ingestion pipeline **file-format-first** (MT940 upload → normalized `TransactionIngested`), ship reconciliation value immediately; then bolt an aggregator adapter onto the *same* pipeline for daily automation; revisit direct AISP only if NAJEM becomes a multi-tenant SaaS where aggregator per-account fees dominate. This also naturally supports the **multiple-bank-source lifecycle**: a file source and an API source are just two `BankSource` kinds; the fundacja's new account is a new `BankSource` connected while the old one is drained and closed.

## 5. Domain events implied

**Aggregate: `BankSource`** (one per bank account feed; kind = `manualFile | aggregatorConsent | directApi`)

Consent lifecycle states (for API-backed sources):
`Pending → Active → RenewalDue (T-14d before 180-day expiry) → Expired → Revoked | Superseded (account switched, e.g. fundacja) → Archived`

- `BankSourceRegistered` — account IBAN, owner, purpose (`operating | deposit`), kind
- `BankConsentGranted` — owner completed SCA; consentId, validUntil (≤180 days)
- `ConsentRenewalRequested` — emitted at RenewalDue; drives owner nudge (email/SMS + reconnect link)
- `BankConsentRenewed` / `BankConsentExpired` / `BankConsentRevoked`
- `BankSourceSuperseded` — ownership moved (fundacja): old source marked, successor linked; reconciliation history preserved
- `BankSourceArchived`
- `StatementFetched` — a fetch/upload happened (API pull, webhook, or MT940 file upload); statementId, period, hash (idempotency)
- `StatementFetchFailed` — incl. reason `consentExpired | bankDown | rateLimited(4/day)` → hotspot for the daily-ingestion SLA
- `TransactionIngested` — normalized entry: bookingDate, amount, counterparty, rawTitle; deduped by bank ref + hash
- `TransactionMatched` / `TransactionUnmatched` — reconciliation against charges by `paymentReference` (feeds F3 one-transfer-many-components allocation)
- `DepositAccountTransactionIngested` — same pipeline, deposit-purpose source; feeds F1 deposit ledger

Policy: *daily at 06:00, for every Active API source → fetch; for manualFile sources with no `StatementFetched` in N days → `StatementOverdueDetected` (nag the manager).*

## 6. Sources

- PolishAPI status/standard: https://www.fiskil.com/open-finance-tracker/standard/polishapi , https://www.openbankingtracker.com/regulation/poland-polish-api , https://polishapi.org/en/commercial-banks/
- BNP PL open banking + portal: https://www.bnpparibas.pl/en/english-info/open-banking , https://gopsd2.bnpparibas.pl/pl/
- KNF AISP registration: https://www.fintech.gov.pl/dla_rynku/procesy_licencyjne/platniczy/AISP/Rejestracja_AISP , https://www.knf.gov.pl/dla_rynku/procesy_licencyjne/platniczy/AISP/Podstawowe_obowiazki_AISP , https://rpms.pl/jak-swiadczyc-uslugi-dostepu-do-informacji-o-rachunku-ais/
- 180-day SCA renewal: https://www.projectivegroup.com/psd2-alert-authentication-period-for-account-information-services-extended-to-180-days/ , https://www.eba.europa.eu/publications-and-media/events/consultation-amending-rts-sca-and-csc-under-psd2 , https://plaid.com/blog/180-days-is-not-enough/
- 4x/day rule: https://developer.gocardless.com/bank-account-data/overview (RTS 2018/389 art. 36(5))
- eIDAS QWAC/QSEAL: https://www.actalis.com/qwac-certificates , https://trustzone.com/qualified-certificates-psd2-qwacs-and-qseals/
- Kontomatik: https://www.kontomatik.com/ , https://developer.kontomatik.com/ (BNP Paribas GOonline in coverage)
- Enable Banking: https://enablebanking.com/ , https://enablebanking.com/docs/faq/ , https://www.g2.com/products/enable-banking/pricing
- Tink: https://www.openbankingtracker.com/api-aggregators/tink , https://merchantmachine.co.uk/open-banking-payments/tink/
- Salt Edge: https://www.saltedge.com/products/account_information/coverage , https://blog.finexer.com/salt-edge-pricing/
- GoCardless BAD wind-down: https://www.openbankingtracker.com/guides/free-open-banking-apis , https://forum.invoiceninja.com/t/gocardless-nordigen-service-no-longer-available-alternative-needed/22576
- Autopay/Blue Media AIS: https://autopay.pl/baza-wiedzy/blog/fintech/bnp-paribas-rusza-z-otwarta-bankowoscia-we-wspolpracy-z-blue-media
- GOonline Biznes statements (PDF/MT940/templates): https://www.bnpparibas.pl/_fileserver/item/1534072 , https://www.bnpparibas.pl/_fileserver/item/1521369 , https://www.bnpparibas.pl/_fileserver/item/1540497
- BNP Paribas Connect / GOconnect Biznes (host-to-host): https://www.bnpparibas.pl/przedsiebiorstwa/bankowosc-elektroniczna/goconnect-biznes , https://www.bnpparibas.pl/_fileserver/item/1512237
- MT940 import ecosystem (parse-ability proof): https://www.emszmal.pl/index.php?zobacz=integracje_import_wyciagow_bankowych_mt940 , https://pomoc.fakturownia.pl/134301415-Import-platnosci-BNP-Paribas
