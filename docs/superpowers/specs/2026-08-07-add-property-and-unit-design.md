# Adding a property, and adding a unit to it

**Date:** 2026-08-07
**Status:** approved, ready to plan

## What this builds

Two write screens over commands that already exist and already work over REST:
`PortfolioService.createProperty` and `PortfolioService.addUnit`. Until now the only way to get a
building into NAJEM was `curl`. `/properties` and `/properties/{id}/units` are read-only boards fed
by Reporting; this puts the two "Dodaj" buttons on them and builds what the buttons open.

Nothing here invents a concept. Every rule it enforces — who owns what, what a property is, when
shares are wrong — is already written down in the PM domain and is merely unreachable from a
browser.

## Routes

```
GET  /properties/new                       phase 1 — address + owner picker
POST /properties/new/owners                add a drafted owner (pick, or create inline)
GET  /properties/new?owners=done           phase 2 — a share input per drafted owner
POST /properties                           create; redirect to the new property's units screen

GET  /properties/{propertyId}/units/new    name + base rent
POST /properties/{propertyId}/units        create; redirect depends on which button
```

### Why the property form is two phases

Picking an owner is a server round-trip, and a round-trip loses whatever is typed in a *sibling*
form — the browser submits one form, not the page. A single screen holding both the address, the
share inputs and the picker would silently reset the shares every time the manager searched for a
co-owner.

Phase 1 carries the address as a hidden field on both picker forms, so it survives. Phase 2 asks
for shares once, after the owner list is settled, so there is nothing left to lose. This is the
same shape as `ReserveScreenController`'s `parties` → `parties=done` → terms flow, deliberately:
the manager learned that flow last week, and a second write screen that behaves differently for no
reason is a cost with no purchase.

### Why add-unit is its own screen with "save and add another"

A twenty-flat building is entered in one sitting. `save` redirects to the units board; `zapisz i
dodaj kolejny` re-renders the blank form and names the unit just added, so the manager can see the
work accumulating without leaving the form. An inline form on the units board was rejected: that
board is served from Reporting and is read-only by design, and putting a write on it mixes the two.

## Decisions taken, with the reasoning

| Decision | Chosen | Why |
|---|---|---|
| Property form fields | address + owners | `Owner(contactId, sharePercent)` is what a property is *for*. There is no edit-owners screen, so "add them later" means never. |
| Rent target | not asked for | A second command and a second append, and no screen reads `rentTarget`. YAGNI. |
| Minimum owners | at least one | A property with no owner is a record of nothing, and nothing can fix it afterwards. |
| Shares ≠ 100% | warn, create anyway | `Property`'s own comment: *"Soft check: shares should total 100% — confirm, don't block."* Half-known share registers are real. |
| Landing after create | that property's units screen | The next act after creating a building is putting flats in it. |
| Base rent | required | The units board formats it unconditionally and would NPE on null; a reservation prefills from it. |
| Owner lawful basis | `"contract"` | The agency holds the data because it manages the building under a contract with the owner — Art. 6(1)(b). Reuses `ContactService.registerParty` verbatim, no new method. |

## Changes below the screens

### `Property.create` and `Unit.add` grow guards

Neither factory validates anything today. A blank address, a blank unit name, and a null or
negative base rent are refused **in the domain**, throwing `IllegalArgumentException` — which
`PmExceptionHandler` and `WebErrorAdvice` already map to 400.

This is refactoring rule 10: the domain owns "what is a property". `required` on the input covers
the case where the browser cooperates; the guard covers the case where it does not, and it is
testable in milliseconds without a container.

### `PortfolioService.createProperty` returns its warnings

Today it returns a bare `UUID`, so the ≠100% warning that `Property.warnings()` already computes
has no way out of the service and no screen can show it. It becomes:

```java
/** A created property and the soft warnings raised against it. */
public record CreatedProperty(UUID propertyId, List<String> warnings) {}

public CreatedProperty createProperty(UUID workspaceId, String address, List<Owner> owners)
```

This is the exact shape of `TenancyService.Reservation(tenancyId, warnings)` — same problem, same
answer, and the screen puts the list in a flash attribute the same way `ReserveScreenController`
does. `PortfolioController` adjusts to `.propertyId()`. That is churn in a caller, and refactoring
rule 5 is explicit that avoiding churn is not a correctness argument.

### Nothing else

**No new port, no new adapter, no new table, no migration.** Every read these screens need is
either the parent property's own event stream — `requireOwnsProperty`, which exists — or the
contact search the reserve flow already uses. This is worth stating plainly because the reflex on
the last feature was to reach for a projection, and the correction was that the record was already
in the stream.

The GET on `/properties/{propertyId}/units/new` calls `requireOwnsProperty` before rendering, so
another agency's building 404s at the form rather than only at submit. Rendering a form for a
building the caller cannot write to is a screen that lies about what will happen.

`createProperty` is the one command in the module with nothing to check against — a property that
did not exist a moment ago has no prior owner — so the caller's workspace is stamped on it. That is
already how the service works and needs no change.

## Files

```
NEW  apps/najem-app/src/main/java/pl/najem/app/web/AddPropertyScreenController.java
NEW  apps/najem-app/src/main/java/pl/najem/app/web/AddUnitScreenController.java
NEW  apps/najem-app/src/main/java/pl/najem/app/web/OwnerDraft.java
NEW  apps/najem-app/src/main/resources/templates/property-new.html
NEW  apps/najem-app/src/main/resources/templates/owners-form.html
NEW  apps/najem-app/src/main/resources/templates/unit-new.html
MOD  apps/najem-app/src/main/resources/templates/properties.html      "Dodaj nieruchomość"
MOD  apps/najem-app/src/main/resources/templates/units.html           "Dodaj lokal"
MOD  modules/propertymanagement/.../domain/Property.java              creation guard
MOD  modules/propertymanagement/.../domain/Unit.java                  creation guard
MOD  modules/propertymanagement/.../application/PortfolioService.java CreatedProperty
MOD  modules/propertymanagement/.../adapter/rest/PortfolioController.java
```

### `owners-form.html` is a separate file, and separate from `parties-form.html`

*Separate file* because a `th:fragment` declared inline in a page is still part of that page's
document: Thymeleaf renders it where it sits **and** again wherever it is inserted. That shipped a
duplicate, unlabelled picker last week and no test caught it, because every assertion asked whether
the form was present and none asked how many there were.

*Separate from `parties-form.html`* because the hidden fields are entirely different — `address`
plus `owner`, versus `interestId` plus `tenant` plus `guarantor` plus `role`. A shared fragment
would take five parameters of which each caller passes null for three. Two focused fragments beat
one that knows about both flows.

### `OwnerDraft`

Mirrors `PartiesDraft`: parse the repeated `owner` request params into an ordered, de-duplicated
`List<UUID>`; reject a value that is not a UUID (`InvalidContactIdException`); reject the same
contact twice (`DuplicatePartyException`) — one person owns one combined share, not two entries
that a manager then has to notice sum wrongly.

## Amounts, inherited not fixed

Base rent renders as `2 500,00` on the units board and is *typed* as `2500.00` in the form, because
`@RequestParam BigDecimal` is Spring's default binder — which is what every amount on the reserve
screen already uses — and `<input type="number" step="0.01">` always submits a dot regardless of
locale.

The asymmetry is real and is being inherited on purpose. Introducing a Polish-comma parser for one
new field would make this field behave differently from every other amount input in the
application, which is worse than the inconsistency it fixes. If it is fixed it should be fixed for
all of them at once, and that is not this change.

## Errors

| Condition | Result |
|---|---|
| Blank address, blank unit name, null/negative base rent | `IllegalArgumentException` → 400 |
| Property belongs to another agency | `UnknownInThisWorkspaceException` → 404, on the GET and the POST |
| `owner` param is not a UUID | `InvalidContactIdException` → 400 |
| Same contact drafted twice as owner | `DuplicatePartyException` → 400 |
| Share is not a number | binder failure → 400 |
| Shares total ≠ 100 | **created**, warning in the flash |
| Phase 2 reached with no owners drafted | back to phase 1 with a message; a property needs an owner |

Absence and foreign ownership give the same answer, because saying which would confirm that another
agency's id is real. That is `requireOwnedBy`'s existing rule, not a new one.

## Freshness

`/properties` and `/properties/{id}/units` are fed by Reporting's projections, which are eventually
consistent — so the property just created may not be on the board for a moment.

This needs nothing new. `ProjectionFreshnessAdvice` already puts `projectionsBehind` on every model
in this package and the layout already renders the banner, precisely so that a manager who has just
created a property and does not see it can tell *"not yet"* from *"it didn't work"*.

## Testing

### Fast tier — `modules/propertymanagement`, in the ~45s build

- a blank or whitespace-only address is refused
- a blank or whitespace-only unit name is refused
- a null base rent is refused; a negative base rent is refused; zero is accepted (a rent-free
  service flat is a thing, and the domain has no opinion about it)
- owners totalling exactly 100 raise no warning
- owners totalling 90 raise one warning, and the message names 90 — not "shares are wrong"
- `createProperty` returns the same id it appended the `PropertyCreated` event under, together with
  the warnings

### Integration tier — `@Tag("integration")`, mirroring `ReserveScreenTest`

- happy path end to end: a property with two owners at 50/50, then a unit inside it, landing on
  that property's units screen with the unit visible
- searching for an owner mid-form does not lose the address already typed
- an owner nobody has met yet is created inline and appears in the draft, with `lawful_basis` =
  `contract` in `contacts_person`
- *zapisz i dodaj kolejny* re-renders a blank unit form and names the unit just added; *zapisz*
  redirects to the units board
- shares at 60/30 create the property and the flash names the total
- the same contact drafted twice as an owner is refused
- another agency's property 404s on both the add-unit form and its POST
- the owner picker fragment appears exactly **once** on the page — asserted as a count, because the
  bug this catches was invisible to every present/absent assertion

### And then run it

Click both screens through in the browser against the demo database. The last feature shipped three
defects that the entire suite structurally could not see — a migration ordering conflict, a
double-rendered fragment, and a SpEL expression that 500'd the page — and all three were found in
under a minute of actually using the app. Every defect found that way gets a test proven red
against the old code before it is fixed.
