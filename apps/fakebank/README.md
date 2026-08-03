# FakeBank

A deterministic test-fixture engine that stands in for the landlord's bank. It is not a demo
sandbox: it exists so accounting's matching ladder and the e2e suite have reproducible bank data.
The same request always produces identical lines — no clock, no randomness, no persistence.

Runs standalone on port 8081: `./gradlew :apps:fakebank:bootRun`

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
never hardcode a calendar. An unknown `name` is a 400.

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
| `lump-sum` | 2×A at D, title naming two references | One transfer, many charges |
| `third-party-payer` | A at D, empty title, payer `ANNA KOWALSKA` on a stable non-tenant account | Tier 3 remembered-payer mapping |
| `outgoing-debit` | 287.43 DBIT at D+1, `OPLATA ZA MEDIA` | A line that must NOT become a rent payment |
| `foreign-currency` | A at D in EUR | Non-PLN as a classifiable fact |

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

## Not here

MT940 export is Phase 2, landing together with its parser so producer and consumer are written
against each other rather than a guess.
