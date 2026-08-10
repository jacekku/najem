# Unit screen: registering a lead's interest

A caller phones the agency about a specific unit. The manager, standing on that unit's row, records
who rang and what they would pay. That is the whole scenario, and it is entirely a UI scenario —
the domain behind it was built in phase 1 and has never had a screen.

## What already exists

Verified against the running app and the source on 2026-08-07, not from memory.

| | |
| --- | --- |
| `POST /api/contacts` | registers a person: `givenName`, `surname`, `email`, `phone`, `lawfulBasis`, optional `infoClauseServedAt`, `retainUntil` |
| `POST /api/contacts/{contactId}/interests` | `{unitId, willingToPay, desiredStart}` — this *is* "assign a contact to a unit" |
| `GET /api/contacts/units/{unitId}/interests` | the active interests in a unit |
| `DELETE /api/contacts/interests/{interestId}?on=` | withdraw |
| `GET /api/contacts/search?q=` | `ContactDirectory.search`, capped at 50 |

Events: `ContactRegistered`, `InterestRegistered`, `InterestWithdrawn`.

And what does not exist: **no contacts UI at all.** The templates are `home, agencies, properties,
units, bank, report, search, timeline, login`. There is no unit detail screen — `/properties/{id}/units`
is a flat read-only table. The masthead search box searches properties and units only, although
`ContactDirectory.search` is built and served.

There is also **no lead lifecycle beyond registered and withdrawn** — no stages, no qualification,
and nothing connects an interest to the tenancy it becomes. That is out of scope here and is named
so that nobody reads this spec as having delivered it.

`docs/event-storming/property-management-domain-model.md` describes this concept as
`LeadRegistered {lawfulBasis, infoClauseServedAt, retainUntil}`, and `ContactLifecycleTest` registers
exactly this kind of person with `lawfulBasis: "legitimate-interest"` — tenants get `"contract"`.
The vocabulary is not being invented here.

## The screen

`GET /units/{unitId}` — a new `UnitScreenController` and a new `unit.html`. The unit name in
`units.html` becomes the link to it.

```
m. 2 — ul. Marszałkowska 12          [Pustostan]  czynsz 2 850,00

Zainteresowani (1)
  Piotr Nowak   p.nowak@…  +48…   2 900,00   od 2026-09-01   (Wycofaj)

Dodaj zainteresowanego
  Szukaj osoby: [ nowak ] (Szukaj)
    Piotr Nowak  p.nowak@example.com  (Wybierz)
  — albo nowa osoba —
  [Imię] [Nazwisko] [E-mail] [Telefon]
  [ ] Przekazano klauzulę informacyjną
  [Gotów płacić] [Od kiedy]  (Dodaj)
```

One template, three states, all reached by GET so the screen stays bookmarkable and there is no
second template to keep in step:

- **nothing typed** — the search box and the new-person form
- **`?q=nowak`** — matching people listed above the new-person form, each with a `Wybierz` link
- **`?contactId=…`** — the chosen person shown locked in, only `willingToPay` and `desiredStart` left

Searching first is what stops a repeat caller becoming a second person — which matters beyond
tidiness, because erasure deletes one row and would then miss the other.

## Reads

**`UnitBoardQuery.forUnit(workspaceId, unitId, asOf)` → `Optional<Row>`.** The existing
`forProperty` SQL with `u.unit_id = ?` in place of the property predicate. `Row` already carries
`propertyId`, so the breadcrumb back to the property's unit list needs nothing new.

That class's javadoc argues against a per-unit primitive, and it is right about the case it names:
a board rendering N units must not call one. A single unit screen calls it once. The distinction
goes into the javadoc rather than being left for the next reader to re-litigate.

**`UnitInterestQuery` — a new driven port in `contacts.application`.** Joins `contacts_interest` to
`contacts_person`, returning:

```java
record InterestedParty(UUID interestId, UUID contactId, String givenName, String surname,
                       String email, String phone, BigDecimal willingToPay, LocalDate desiredStart)
```

`Query` and not `Repository` or `Projection`, per A7's third suffix and for the same reason as
`SuggestionQuery`: it joins two tables the module already owns and there is no denormalized store to
rebuild, so `Projection` would be a claim about storage that is not true and `Repository` is taken
by the record itself.

The alternative — `InterestService.forUnit` followed by `ContactDirectory.find` per row — is an N+1
in a controller, which is the fan-out `UnitBoardQuery`'s javadoc exists to warn about. The names
cannot come from Reporting at any price: the PII lookaside means Reporting has never seen a name,
and a projection holding one would survive the `contacts_person` deletion that *is* erasure. That
argument is already written down on `ContactDirectory.search` and this port must not undo it.

Postgres adapter plus an in-memory double, per A5.

## Writes

Both are plain form POST → redirect, matching the bank screen: no htmx, and CSRF arrives free from
Thymeleaf's `th:action`. Both take `WebWorkspace`.

**`POST /units/{unitId}/interests`** — one endpoint, two paths:

- `contactId` present → register the interest against that person
- `contactId` blank → register the person, then the interest

Then `redirect:/units/{unitId}`.

**`POST /units/{unitId}/interests/{interestId}/withdraw`** → `InterestService.withdraw(workspaceId,
interestId, today)`, then the same redirect. POST rather than DELETE because an HTML form cannot
issue one.

`willingToPay` and `desiredStart` stay optional. A caller who asks "what would you take for it?"
has named neither, and both columns are nullable.

The same person may hold two active interests in one unit, and nothing refuses it. That matches
`ContactDirectory.findByEmail`, which offers candidates and lets the manager judge rather than
enforcing uniqueness — and a second interest at a different price after a second phone call is a
real thing, not a mistake. The list shows both; withdrawing is per interest.

## Where the lawful basis lives

`ContactService.registerLead(workspaceId, ContactDetails, boolean infoClauseServed, LocalDate today)`
in the contacts module. It sets `lawfulBasis = "legitimate-interest"`, `infoClauseServedAt = today`
when the box was ticked and null otherwise, and leaves `retainUntil` null.

Rule 10: the basis a lead is registered under is a decision the contacts module owns, not the
screen's opinion. A constant in `UnitScreenController` would work today and would be answered
differently by the next screen that registers a lead.

The checkbox is the one fact on that form that is genuinely the manager's to assert, which is why it
is a field and the basis is not. Defaulting `infoClauseServedAt` to today with no field would have
the application claim the clause was served when nobody said so.

## Tests

Two tiers, per rule 16, and the fast one has to be able to fail.

**Fast (no container):**

- an in-memory `UnitInterestQuery` double that models the join — stores the rows and resolves the
  name from the person it was given, rather than echoing back a name handed to it (rule 14)
- the create-or-reuse branch extracted from the controller so it is testable without booting
  anything. The precedent is `SearchGroupingTest`: grouping moved out of the template after an
  expression that failed only when there was something to show, and every test passed because no
  test ever produced a hit.

**Integration (`@Tag("integration")`, MockMvc + Testcontainers):**

- register a lead for a unit → they appear on the unit screen
- withdraw → they are gone from it
- pick an existing contact from search → no second `contacts_person` row
- `forUnit` returns the same row for a unit that `forProperty` returns in its list — the two must not
  drift, and only the database can say (rule 15)

## Not in scope

- a `/contacts` section or any person-detail screen
- people in the masthead search
- lead stages, qualification, or converting an interest into a tenancy — none of it is modelled, and
  building any of it is domain work rather than a view
