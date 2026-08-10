# Reserving a tenancy for the lead who said yes

The lead on a unit's *Zainteresowani* list has confirmed they want the space. The manager, standing
on that unit's screen, turns them into a signed reservation.

Unlike the screen that put them on that list, this is **not** only a UI change. The reservation
domain is complete and unreached; what is missing is the link between a lead and the tenancy they
become, and the RODO consequence of becoming one.

## What already exists

Verified against the source on 2026-08-07, not from memory.

| | |
| --- | --- |
| `TenancyService.reserve(workspaceId, ReserveTenancy)` | registers the period on the unit's calendar, appends `TenancyReserved`, arms the start timer, returns `Reservation(tenancyId, warnings)` |
| `TenancyService.cancelReservation(workspaceId, tenancyId, reason)` | releases the period, disarms the timer |
| `TenancyService.activate(workspaceId, tenancyId, on)` | early activation ahead of the armed timer |
| `POST /api/pm/tenancies` | the only caller of `reserve` today, and only `tools/seed-demo.sh` calls it |
| `GET /tenancies/{id}/timeline` | Reporting's account of one tenancy — a screen that exists |

`Tenancy.reserve` refuses two things hard: no tenant contact, and no explicit legal form. Everything
else is a soft warning from `Tenancy.warnings()` — a deposit over the statutory cap for the legal
form, a breakdown that does not sum to the total, a fixed term longer than ten years. `Unit`'s
no-overlap check is the module's one hard invariant and throws `OverlappingTenancyException`.

And what does not exist:

- **no screen anywhere reserves a tenancy.** No form, no controller, no template.
- **nothing connects an interest to the tenancy it becomes.** No event, no column. The unit-interest
  spec named this as out of scope and it still is not built.
- **no contact can change its lawful basis.** `ContactRegistered` carries it and `correctDetails`
  does not touch it, so a lead registered under `legitimate-interest` stays there forever.

## The scenario

Each row of *Zainteresowani* gains a **Rezerwuj** button. Pressing it **is** the confirmation.

There is deliberately no intermediate "confirmed" lead state. A state that exists only between two
clicks is a state nobody is ever in when the phone rings again, and it would need its own event, its
own column, and its own answer to "what if they then change their mind". In this domain a hard
reservation already means *the agreement is signed* — `ReserveTenancy`'s own javadoc says so — which
is exactly what confirming means.

## The screen, in two phases

`GET /units/{unitId}/reserve?interestId=…`

**Phase 1 — parties.** The lead is shown as first tenant. Co-tenants and guarantors are added here.
The draft lives in the query string:

```
/units/{unitId}/reserve?interestId=I&tenant=T1&guarantor=G1&guarantor=G2&role=guarantor&q=kowal
```

Bookmarkable, no session, no server-side draft — the same shape the unit screen's three GET states
already use. `role` says which picker is open so one search box serves both.

**The lead is not in the `tenant` list.** They come from `interestId`, which is the whole reason this
screen was opened, and they cannot be removed from it — a reservation for somebody else is a
different reservation, started from that person's row. `tenant` holds only the co-tenants added here.
An id appearing twice, or in both roles, is refused: `ReserveTenancy` would carry a duplicate and
`Tenancy` has no rule against one.

**Phase 2 — terms.** Reached by `&parties=done`. One form, submitted once:

```
Rezerwacja — m. 2                        Najemca: Piotr Nowak
                                         Poręczyciel: Anna Zielińska

Forma prawna   (•) zwykły ( ) okazjonalny ( ) instytucjonalny
Od             [2026-09-01]
Czas trwania   (•) na czas nieokreślony  ( ) do [          ]
Czynsz łącznie [2 850,00]
  [ ] umowa wyodrębnia składniki
      czynsz [      ] opłaty administracyjne [      ] zaliczka na media [      ]
Dzień płatności [10]
Kaucja          [5 700,00]
Tytuł przelewu  [NAJEM/M2/2026-09]

                                                        (Rezerwuj)
```

**Why two phases.** Adding a person is a page load. On one screen, every added guarantor would wipe
the typed amounts. Splitting is cheaper than round-tripping fifteen form fields through the query
string, and it keeps the inline-creation POST simple: it only ever returns to phase 1.

Two templates, `reserve-parties.html` and `reserve-terms.html`. They share no content, so one
template with a top-level `th:if` would be two templates wearing one filename.

### Adding a person

Both pickers have the unit screen's shape — search existing, or **albo nowa osoba** with the inline
name/e-mail/phone fields. Guarantors are the reason: an agency does not already hold them.

`POST /units/{unitId}/reserve/parties` registers the person and redirects back to phase 1 with their
id appended in the named role.

**Their lawful basis is `contract`, not `legitimate-interest`.** Nobody phoned about a unit; the only
reason the agency holds a guarantor's details is the agreement being signed. `ContactService` gains
`registerParty(workspaceId, details, infoClauseServed, today)` beside `registerLead`, and the two
must not be merged for the reason `registerLead`'s javadoc already gives.

A person created and then abandoned mid-form is a real orphan. They are visible on a search and
erasable, which is the same trade the existing person-then-interest path already takes and names.

## Contacts gains two events

### `InterestConverted`

```java
record InterestConverted(UUID workspaceId, UUID interestId, UUID contactId, UUID unitId,
                         UUID tenancyId, LocalDate convertedOn)
```

Appended to the Contact stream like its two siblings, registered in `ContactsEventTypes`.
`V20260807120000__contacts_conversion.sql` adds `converted_to_tenancy_id uuid` to `contacts_interest`;
the status
becomes `'converted'`. `activeForUnit` already filters `status = 'active'`, so a converted lead drops
off the unit screen with no change to that query.

**This forces one repair, and the repair is the point.** `InterestRepository.contactOf` deliberately
ignores status, so withdrawing a *converted* interest would flip it to `'withdrawn'` and silently
drop the tenancy link — losing the only record that the lead was won. It becomes:

```java
Optional<Interest> find(UUID workspaceId, UUID interestId);
```

`Interest` already carries `status`. Both `withdraw` and `convert` refuse anything that is not
`'active'`, throwing `InterestNotActiveException`. One lookup, one state machine, rather than each
command remembering separately — rule 9's structural invariant over the procedural one.

The workspace gate does not weaken: `find` filters on `workspace_id` exactly as `contactOf` did, and
its javadoc's warning about an unfiltered implementation carries over verbatim to the in-memory
double.

### `LawfulBasisChanged`

```java
record LawfulBasisChanged(UUID workspaceId, UUID contactId, String lawfulBasis, LocalDate changedOn)
```

with `ContactService.becameContractParty(workspaceId, contactId, today)`, applied to every tenant and
every guarantor after the reservation lands. `ContactRepository` gains two methods, because
`find` returns `ContactDetails` and a basis is not a detail:

```java
Optional<String> lawfulBasisOf(UUID workspaceId, UUID contactId);
void updateLawfulBasis(UUID workspaceId, UUID contactId, String lawfulBasis);
```

`lawfulBasisOf` returning empty is a contact this workspace does not know, and
`becameContractParty` refuses it as `NoSuchContactException` rather than updating no rows quietly —
the scoped-update-matches-nothing failure `ContactDirectory.requireIn` was written to close.

Retention rules key on the basis, so a signed tenant sitting on `legitimate-interest` makes the RODO
record say something untrue about why the agency holds their data.

**Idempotent by reading first**: a contact already on `contract` gets no event and no update. A
guarantor created inline moments earlier is already `contract`, and appending a change from
`contract` to `contract` would put a record of a decision nobody made into a stream that exists to
prove what was decided.

`retainUntil` is still not set. How long an agency keeps a former tenant is a retention policy nobody
has decided, and this change is not where that gets invented.

## The write, and its order

`POST /units/{unitId}/reserve`:

1. `tenancies.reserve(workspaceId, command)` → `tenancyId`, warnings
2. `interests.convert(workspaceId, interestId, tenancyId, today)`
3. `contacts.becameContractParty(...)` for each tenant and each guarantor
4. `redirect:/tenancies/{tenancyId}/timeline`, warnings in a flash attribute

**The reservation goes first** because it is the fact, and it is the only step that can fail hard. A
failure after it leaves a real tenancy beside a stale lead — visible on the unit screen and fixable
by hand. Converting first would mark a lead as won for a tenancy that does not exist, and nothing
would show that. Same argument the existing person-before-interest ordering already makes.

These are three services in three modules, called from one driving adapter. That is A1 and A2 as
written — `UnitScreenController` already holds Reporting's query beside two contacts services — and
it is deliberately not an event handler in contacts listening for `TenancyReserved`. PM does not know
the `interestId`; matching on `(contactId, unitId)` would be the application guessing which of two
interests the manager meant, and the manager already said.

## Cancelling a reservation

Managers can now create reservations from the UI, so they need an undo. The tenancy timeline gains
**Anuluj rezerwację** → `POST /tenancies/{tenancyId}/cancel` → `cancelReservation` → back to the
timeline.

Shown only when the tenancy is still reserved, and the screen asks **the stream**, not a projection:

```java
// TenancyService
public boolean isReserved(UUID workspaceId, UUID tenancyId)
```

which loads `store.load(tenancyId, "Tenancy")`, rebuilds with `Tenancy.from(events)`, calls
`requireOwnedBy`, and answers from the `state` the aggregate already holds — one new accessor on
`Tenancy`.

**This was nearly a new read port over `pm_tenancy.state`, and that would have been wrong.** PM is
event-sourced: the stream is the record and `pm_tenancy` is a derived copy written after the append.
`cancelReservation` decides from the stream, so a button deciding from the projection would disagree
with the command it triggers on a day nobody is watching — which is precisely the one-question-two-answers
failure that retired `WorkspaceGuard`. `TenancyProjection` stays write-only, as its javadoc requires.

The cost is one stream load per timeline render, for one aggregate, once. `cancelReservation` pays
the same read anyway.

The interest is **not** un-converted by a cancellation. The lead did sign; the reservation was then
undone, and rewriting the lead's history to say otherwise would lose that. A manager who wants them
back on the list registers a fresh interest.

## Errors

| | |
| --- | --- |
| `OverlappingTenancyException` | caught in the controller; phase 2 re-renders with the message and every submitted value still in its field |
| `UnknownInThisWorkspaceException` | 404 through `WebErrorAdvice`, the same undifferentiated answer a foreign unit already gets |
| `NoSuchInterestException`, `NoSuchContactException` | 404, already mapped |
| `InterestNotActiveException` | 409 where it is still reachable — the unit screen's withdraw button, where the interest exists and is yours and saying 404 would be false. The reserve screen's own `activeLead` filters through `activeForUnit`, which already excludes `converted`, so a re-posted reserve form (a stale tab, or the back button after reserving) 404s via `NoSuchInterestException` before `convert` can throw this — the handler stays for the path that does still reach it |
| `IllegalStateException` from `cancelReservation` | 409. The button is not shown for a non-reserved tenancy, but a stale tab can still post one |

The overlap is caught rather than mapped because it is the manager's input that caused it, and an
error page would be correct and would also throw away the form.

## The payment reference

Suggested as `NAJEM/<unit name, uppercased, non-alphanumerics collapsed>/<yyyy-MM>` — `m. 2` starting
September 2026 gives `NAJEM/M2/2026-09` — from a pure static method, and editable.

**Two units named `m. 2` in different properties, starting the same month, produce the same
suggestion.** That goes in the javadoc rather than being left to be discovered: reconciliation matches
bank lines on this string, so a duplicate means one transfer matches two tenancies.
`UnitBoardQuery.Row` carries no address to discriminate with, and the manager can see and change the
value.

**No uniqueness check.** No port exists to ask accounting whether a reference is taken, and building
one is a different change with its own design.

## Tests

Two tiers, per rule 16, and the fast one has to be able to fail.

**Fast (no container):**

- the reference suggestion — a pure static method: sanitising, the month, and a case that collides
- the parties draft — parsing repeated `tenant` / `guarantor` params, rejecting a malformed id the
  way `chosen` already does, and refusing a duplicate id in two roles
- the interest state machine on the in-memory `InterestRepository`: convert an active interest,
  refuse converting it twice, refuse withdrawing a converted one. The double stores the status and
  reads it back, as the SQL does — it must not derive the answer from what it was just handed
  (rule 14)
- `becameContractParty` idempotence: a contact already on `contract` appends no event
- wire names for `InterestConverted` and `LawfulBasisChanged` pinned in a test (rule 12)

**Integration (`@Tag("integration")`, MockMvc + Testcontainers):**

- reserve from an interest → the tenancy exists, the interest is `converted` with
  `converted_to_tenancy_id` set, it is gone from the unit screen, and the lead's `lawful_basis` is
  `contract`
- a guarantor created inline exists with basis `contract` and is named on the reservation
- reserving over an existing tenancy re-renders phase 2 with the message and creates **nothing** —
  no tenancy, no conversion, no basis change
- cancel a reservation → the unit is free again and a second cancel is refused

**Tripwires mutated before believed (rule 21):** breaking the status guard in `convert` must turn the
convert-twice test red, and dropping the workspace filter from the in-memory `find` must turn the
foreign-interest test red.

## Not in scope

- activating a tenancy from a screen — the armed timer does it, and the API has `activate`
- market state changing when a unit is reserved; marketing and occupancy are separate on purpose
- checking that a payment reference is unique
- ending a tenancy, guarantor removal, or any lead stage beyond registered / withdrawn / converted
