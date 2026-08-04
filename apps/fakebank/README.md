# FakeBank

A deterministic test-fixture engine that stands in for the landlord's bank. It is not a demo
sandbox: it exists so accounting's matching ladder and the e2e suite have reproducible bank data.
The same request always produces identical lines — no clock, no randomness, no persistence.

Runs standalone on port 8081: `./gradlew :apps:fakebank:bootRun`

## Looking at what it holds

`http://localhost:8081/` lists the seeded accounts; each links to its statements and their lines.

The screens render through the same `StatementRenderer` the MT940 export uses, rather than reading
`TransactionStore` a second way — a page with its own view of the statements could disagree with
the file accounting actually imports, and seeing what the other side receives is the only reason to
look. **Pokaz plik MT940** goes further and serves `GET /api/accounts/{iban}/statement.mt940`
itself, so what appears is byte-for-byte the export.

They are read-only. Seeding stays on the API, so nothing on a screen can put a demonstration into a
state the API did not — a test asserts the pages contain no form.

The accounts list's last column sums credits less debits **across currencies**, which is arithmetic
nonsense and labelled as such on the page: it is a direction-of-travel figure. FakeBank knows no
opening balance, so a real balance cannot be computed from what it has, and showing a plausible one
would be a number from nowhere.

## Seeding a scenario

```
POST /api/scenarios
{ "name": "partial-then-topup",
  "iban": "PL61109010140000071219812874",
  "reference": "NAJEM/M1/2026",
  "amount": "2500.00",
  "anchorDate": "2026-09-01" }

201 { "seeded": 2, "externalIds": ["partial-then-topup/NAJEM-M1-2026/0", "..."] }
```

`anchorDate` is the charge's due date; every scenario expresses its dates relative to it, so tests
never hardcode a calendar.

Every field above except `secondReference` is required, and a request missing one is a **400**
naming the field. The names matter: `scenario` and `dueDate` are the plausible guesses and bind to
nothing. They used to leave the record half-built and surface as a 500 — which tells a caller the
server is broken when their request was. An unknown `name` is likewise a 400.

`secondReference` is optional and read by `lump-sum-two-tenancies` alone, which is refused without
it. It is not derived from `reference` on purpose: a derived reference would share a segment with
the first, and a matching rule keyed on that similarity would look like it handled a cross-tenancy
transfer while having recognised one tenancy twice — the fixture would pass for the wrong reason.

Seeded lines are then served by the existing endpoint, unchanged since Phase 0:
`GET /api/accounts/{iban}/transactions?since=YYYY-MM-DD`

Storage is a singleton for the lifetime of the process. Tests that must not see each other's lines
should seed into their own IBAN rather than rely on cleanup.

## The scenarios

`A` = the requested amount, `D` = the anchor date.

| Name | Lines | What it exercises |
|---|---|---|
| `on-time` | A, exact reference, at D | Tier 1, the happy path |
| `late` | A at D+6, valued D+8 | Arrears timing; booking/value date divergence |
| `partial` | 60% of A at D | Partial allocation |
| `partial-then-topup` | 60% at D, remainder at D+4 | Many transfers, one charge |
| `overpay` | 120% of A at D | Overpayment handling |
| `wrong-reference` | A at D, title `najem m1 2026` | Tier 2 fuzzy reference matching |
| `no-reference` | A at D, empty title | Tiers 3–4, counterparty-based matching |
| `duplicate` | Two identical credits, distinct ids and bank references | Deduplication of a real bank duplicate |
| `reversal` | Credit at D, equal debit at D+3 | Returned transfer |
| `lump-sum` | 2×A at D, title naming the reference twice | One transfer, many charges of ONE tenancy |
| `lump-sum-two-tenancies` | 2×A at D, title naming `reference` and `secondReference` | One transfer, two **independent** tenancies — the ladder must REFUSE it |
| `third-party-payer` | A at D, empty title, payer `ANNA KOWALSKA` on a stable non-tenant account | Tier 3 remembered-payer mapping |
| `outgoing-debit` | 287.43 DBIT at D+1, `OPLATA ZA MEDIA` | A line that must NOT become a rent payment |
| `foreign-currency` | A at D in EUR | Non-PLN as a classifiable fact |

### The cross-tenancy case is a negative fixture

`lump-sum-two-tenancies` is one transfer from one payer settling two tenancies that share nothing —
different tenants, different units. Accounting's allocation engine can *represent* that split
(`PaymentAllocated` carries a `tenancyId` per allocation), but **the matching ladder must never
suggest it**: no rung can honestly claim which two tenants a single transfer was meant for, and a
confident split across a tenancy boundary is worse than no suggestion at all. A human resolves it
through `POST /api/acc/payments/{id}/allocate`.

So the assertion this fixture exists for is an absence — the payment reaches the manual queue and
carries no suggestion. It pins that the ladder stays honest exactly where guessing is easiest.

## Field conventions

Amounts are **always positive**; direction is carried solely by `creditDebitIndicator`
(`CRDT` / `DBIT`, ISO 20022) and never duplicated as a sign.

`bankReference` is the bank's own reference and is deliberately unrelated to the tenant's payment
reference in `title` — telling those two apart is the point of the field.

`counterpartyIban` is a stable synthetic account: the same tenant keeps the same account across
scenarios and months, which is what makes the remembered-payer mapping testable. The third-party
payer has their own, equally stable, different account.

The first four fields (`id`, `amount`, `title`, `bookingDate`) are always present — that is the
Phase 0 shape. The other six are nullable and omitted from the JSON when null.

## Exporting a statement

The same seeded lines, in the format a bank would hand over:

```
GET /api/accounts/{iban}/statement.mt940?since=YYYY-MM-DD   →   text/plain
```

One statement per currency, alphabetically, numbered `1/1`, `2/1`, … — MT940 states the currency
once on the balance fields, so a `foreign-currency` seed cannot share a statement with a PLN one.
Exporting the same seed twice yields byte-identical text.

The title travels in `:86:~20`, the counterparty in `~32`/`~38`, and the bank's own reference after
the `//` in `:61:`. Direction is the `C`/`D` mark; amounts stay positive, as everywhere else here.

**External ids do not survive the trip, by design.** `on-time/NAJEM-M1-2026/0` is a property of this
service's JSON transport, not of the transaction — a real bank has never heard of it. An ingested
MT940 line gets its id from the importing side. `StatementRoundTripTest` therefore compares
amounts, dates, remittance, counterparty and direction, and deliberately not ids.

The parser is `platform/mt940`, a dependency-free library shared with the ingesting side.
