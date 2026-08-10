# Adopting the Najem design system

A design handoff — six screens for a property management and accounting app, drawn at high
fidelity with final colours, typography, spacing and copy — arrived as `.designsystem/`. This spec
is how it becomes the application's appearance.

The handoff's own instruction is the frame for everything below: *"The files in this bundle are
design references created in HTML — prototypes showing intended look and structure, not production
code to copy… take the markup as a spec for layout and values, not as source."* The design-tool
runtime it ships (`support.js`) must never enter this repository. `.designsystem/` is gitignored
for that reason.

## What already exists

Verified against the source on 2026-08-07, not from memory.

The UI is Thymeleaf + a vendored htmx + one hand-written stylesheet. There is no build step, no
npm, no framework. 19 template files, one `najem.css` of 865 lines, all of it in
`apps/najem-app/src/main/resources`.

| | |
| --- | --- |
| `static/css/najem.css` | 865 lines: a token block, then sections for Masthead, Page, Tables, Status, Buttons, Lists, Search, Sign-in |
| `templates/layout.html` | the shell — a horizontal masthead holding brand, agency name, search, a staleness badge, the theme toggle and sign-out |
| 15 screen templates | `home, agencies, properties, property-new, units, unit, unit-new, timeline, report, search, bank, login, parties-form, reserve-parties, reserve-terms` |
| 3 error templates | `error/forbidden, error/no-agency, error/not-invited` |
| `tools/contrast` | a Python WCAG checker that parses `najem.css`. **Not wired into Gradle or CI** — it is run by hand |

The current stylesheet is a deliberate visual language with its rationale written into the file:
warm paper, a serif for headings, hairlines rather than boxes, "Kancelaria" — a register meant to
read like a book somebody keeps rather than a dashboard. It is being replaced. That is a decision
taken with the argument in front of it, recorded here so nobody later reads the replacement as an
accident.

### The three collisions, and how each was settled

**Third-party assets.** `najem.css` carries a rule — *"NO ASSET COMES FROM A THIRD PARTY"* — and
`WebScaffoldTest.theLayoutReferencesNoExternalHost` enforces it. The handoff specifies Instrument
Sans and IBM Plex Mono from Google Fonts.

*Settled:* both fonts are self-hosted as vendored `woff2` under `static/vendor/fonts/`. Both are
SIL Open Font License, so the licence text ships beside them. This is not a compromise on the
design — the typefaces and their rendering are identical — and it keeps a real test green.

**Dark mode.** The app has server-resolved light/dark: a cookie read in `ThemeAdvice`, stamped on
`<html>` before first paint so there is no flash, a toggle in the masthead and again on the login
page, and `ThemeController` with open-redirect hardening on its return path. `ThemeTest` is eight
tests. The handoff designs light only and never mentions a dark variant.

*Settled: dropped.* `ThemeController`, `ThemeAdvice`, `ThemeTest`, both toggles, the `data-theme`
attribute and the `prefers-color-scheme` block all go. The app has one appearance. This deletes
working, tested, documented functionality, and the deletion is the point of the decision rather
than a side effect of it.

**Contrast.** `tools/contrast` exists because *"a colour that fails contrast looks fine to the
person who chose it."* Measured against `bg/surface` `#ffffff`, the handoff's palette does not
clear the thresholds that file checks:

| token | hex | ratio | |
| --- | --- | --- | --- |
| `text/disabled` | `#c3ccd2` | 1.63:1 | below 3:1 |
| `text/placeholder` | `#a8aeb6` | 2.24:1 | below 3:1 |
| `text/table-head` | `#98a0aa` | 2.64:1 | below 3:1 — this is every column head |
| `text/faint` | `#8b929b` | 3.14:1 | below AA |
| `text/label` | `#7b828c` | 3.88:1 | below AA — this is every KPI label |
| `status/warn` | `#a9761c` | 3.97:1 | below AA |
| `border/control` | `#e0e4e8` | 1.28:1 | below 1.4.11's 3:1, and it bounds inputs |

`text/muted` `#6b7280` (4.83:1), `text/body` `#5c636d` (6.07:1), `text/secondary` `#4b5563`
(7.56:1), `accent/green` `#1f7a5c` (5.25:1) and `status/danger` `#b4342a` (6.06:1) all clear AA.
It is the quiet end of the ramp that does not.

*Settled: the palette ships exactly as drawn.* The values above are accepted, not fixed.
`tools/contrast` is updated to parse the new token file and will report these as failures; it stays
in the repository, and its output becomes a known state rather than a gate. It is not deleted,
because the day somebody adjusts a token by eye is the day it earns its keep again.

**One rule of the old stylesheet survives untouched:** *"the colours only differ; they never
decide."* Every status carries words as well as a hue. The handoff agrees by construction — its
status pills are labelled (`4 days late`, `Part paid`, `Due tomorrow`, `Vacant 45d`), not bare
colours. Nothing in this work may introduce a state distinguished by colour alone.

### Language

All 19 templates are `lang="pl"` and the copy is Polish throughout — `Nieruchomości`, `Lokale`,
`Zaległości`, `Pustostan`, `Wycofaj`. The handoff's copy is English and says so itself: *"UI labels
are English; data is Polish… treat the English as placeholder."*

The application stays Polish. New strings from the handoff are translated, reusing the vocabulary
already in the templates rather than inventing terms — this is a domain with statutory language
(`najem okazjonalny`, `wypowiedzenie`, `waloryzacja`), and a screen that invents a word for one of
those is worse than one that is late. Translations are listed for review as part of the work, not
buried in a diff.

## The approach

Four decisions, taken in order, each constraining the next.

1. **Replace wholesale, exactly as drawn.** The mockups are the product's appearance. Kancelaria is
   retired.
2. **Foundation first, then reskin.** Build the design system as a component library, then move all
   19 existing templates onto it in one pass. No new backend work and no new screens in this spec.
3. **Light only.** As settled above.
4. **Polish copy, translated as part of the work.**

### Why a component library rather than a stylesheet

The obvious shape is "rewrite `najem.css` with the new tokens and class names." It is the smallest
diff and it is the wrong answer, because the scaling limit here is not the stylesheet — it is the
markup.

Both detail screens in the handoff use the same card, the same 42px table row, the same status
pill, the same key/value rail. If those exist only as class names, every new screen re-implements
the card's header/body/footer by hand, and the *markup* drifts even while the tokens hold. That is
how a design system erodes: one template at a time, each individually reasonable. And this does not
stop at six screens — the handoff itself names the Ledger tab, the Financials tab, the full property
timeline, three more timeline views on unit detail and the payment-matrix report as designed-but-not-drawn.

So the deliverable is a library with four parts.

### Part 1 — Layered CSS, five files, no build step

```
@layer tokens, base, layout, components, screens;
```

Declared once, at the top of the first file; each file contributes to its own layer via
`@layer components { … }`. Five ordered `<link>` elements in `layout.html`.

The layers are not tidiness. They mean a screen-level rule can never accidentally out-specify a
component, so screen 30 never needs `!important` or a longer selector to win an argument with
screen 3. Cascade order stops being a function of who wrote what first — which is the thing that
makes a large stylesheet unmaintainable.

| file | holds |
| --- | --- |
| `tokens.css` | the handoff's colour table 1:1, the type scale, spacing, radii. Nothing else. This is the file reviewed against the handoff |
| `base.css` | reset, the two `@font-face` blocks, element defaults |
| `layout.css` | the shell (216px sidebar + 60px header bar) and the page grids the six screens use: `1fr/330px`, `1fr/300px`, `320px/1fr`, and step-rail + content |
| `components.css` | card, table, status pill, chip, KPI, field, button, step rail, timeline rail, tenancy band, progress bar, checklist, dropzone, toggle, breadcrumb, tab strip |
| `screens.css` | genuine one-offs only. If this grows quickly, a component was missed — that is what the file is for |

Spacing, radii and type come from the handoff's stated scale. Two of its tokens, `#f0f2f4` and
`#eef0f2`, are near-identical and the handoff notes they could collapse; they are kept as two names
because they mean different things (`neutral/pill` versus `neutral/track`) and collapsing them
loses that.

### Part 2 — Every component is a Thymeleaf fragment

A `components.html` of parameterised fragments, so a screen composes rather than copies:

```
~{components :: pill('danger', '4 dni zwłoki')}
~{components :: kpi(label, value, caption, tone)}
~{components :: card(title, actions, body, footer)}
~{components :: field(name, label, value, required, help)}
```

This is the part that does the real work. A screen cannot drift from the system because there is
nothing to copy — the card's anatomy exists once.

### Part 3 — A component gallery at `/design`

One route rendering every fragment in every state: each pill tone, each field state including focus
and error, the table with rows and empty, the checklist in all three marks.

Two things it buys. The whole system is visible on one page, so drift is something you can see
rather than something you discover on screen 20. And the states the handoff never drew get designed
once, here, rather than improvised per screen — it lists **empty states, loading states, and the
blocking overlap error** as undesigned, and those will otherwise be invented four times.

The gallery renders fragments with fixed sample data and reaches no service, so it needs no
workspace. It is a real route in the application, reachable by a signed-in user; it exposes no
tenant data because it holds none.

### Part 4 — A tripwire test

A test asserting no template under `templates/` contains a hex colour, a `style=` attribute, or a
hardcoded `px` value.

One exemption, and it is narrow: an inline `<svg>`'s own geometry — `viewBox`, `width`, `height`,
`d`, `stroke-width` — is the icon's shape, not a design value, and cannot be moved to CSS. The
exemption is scoped to attributes inside an `<svg>` element and does **not** extend to `fill` or
`stroke` taking a hex; icons inherit `currentColor`. Anything wider than that is the regex being
widened, which is the thing the test forbids.

This repository already works this way — `refactoring.md` rules 20 and 21 are the same instinct.
Two things follow from those rules and are part of the work:

- The test carries its instruction in its javadoc: **fix the template, do not widen the regex.**
  The person who hits this is under pressure to make it green, and widening is the fast way.
- It is broken deliberately once, to watch it go red, and reverted. Rule 21: a tripwire that has
  never been seen to fail is a comment.

## The shell

`layout.html` changes from a horizontal masthead to the handoff's **216px fixed sidebar + 60px
header bar**. Every template renders into it, so this is the change that touches everything.

Where each thing the current masthead carries ends up:

| today | in the new shell |
| --- | --- |
| brand "NAJEM" | sidebar logo lockup — 26px `accent/green` rounded square, white bold "N", wordmark 15px/600 |
| agency name + role | sidebar footer, pinned with `margin-top:auto`, where the mockup puts the 28px avatar and name/role |
| search form | header bar, the 190px field on the right |
| `projectionsBehind` "aktualizacja…" | header bar, beside the search |
| theme toggle | **deleted** |
| sign-out | sidebar footer, with the agency and role |

The staleness badge deserves its reason recorded, because the mockups have no home for it and
dropping it would have been the quiet option. It exists because boards here are eventually
consistent, and a lagging projector renders as a short list that is indistinguishable from a correct
one — so a manager who has just created something cannot tell "not yet" from "it didn't work".
`layout.html` says this is the condition on which an eventually consistent board was accepted. It
stays.

### The sidebar's nine items

The handoff draws nine destinations in two groups — Dashboard, Properties, Tenancies, Payments,
then under an `ACCOUNTING` micro-label: Invoices, Bank feed, General ledger, Owner statements,
Reports. Four have routes today.

All nine render. The unbuilt five are shown in a muted, non-clickable state — the nav has the
shape the design gives it and signals what is coming, without any item being a dead link.

This is a deliberate departure from a rule `home.html` states: *"Only what is wired is a link. A
screen that offers a route to nothing is the same failure as a number that came from nowhere."*
The rule is honoured in its substance — nothing here is a link to nothing — and the comment in
`home.html` is updated rather than left to contradict the sidebar beside it.

Icons in the mockups are deliberate placeholders (14px outlined squares) and the handoff says to
replace them with the repository's icon set. There is no icon set. Inline SVG icons are added
under the same no-third-party rule as the fonts — drawn in the repository, not fetched.

## Reskinning the 19 templates

Every template moves onto the new shell and the fragment library. Grouped by how much changes:

**Structural — these have a designed counterpart and take its layout:**
`properties`, `units`, `unit`, `property-new`, `unit-new`, `reserve-parties`, `reserve-terms`,
`report`, `timeline`.

The mapping is not one-to-one and this spec does not force one. `unit.html` is the nearest thing to
the handoff's *Unit + tenancy detail*, but that mockup's tenancy band, ledger tab and attention rail
have no data behind them yet; `unit.html` takes the header, tab strip and card anatomy, and its
existing content sits inside them. Same for `units.html` against *Property detail*. **Reaching full
fidelity on the six designed screens is not in this spec** — that is the work that follows, screen
by screen, once the foundation exists.

**Chrome-only — no designed counterpart, so they take the shell, cards, tables and buttons and
nothing more:** `home`, `agencies`, `search`, `bank`, `parties-form`, and the three error pages.

`home.html` deserves a note: it is currently four links, and the handoff's Dashboard replaces it
with KPIs, a collection-progress bar and three panels. Almost none of that has a query behind it.
For this spec, `home` is restyled as-is. Building the Dashboard is its own piece of work with its
own backing queries.

**`login.html`** is outside the sidebar shell — it renders before there is an agency — so it takes
the tokens and field components on its own centred layout, and loses its theme toggle.

## Testing

The existing test suite is the constraint that shapes this, and most of it should keep passing
untouched — the controllers, routes and model attributes are not changing. What moves is markup and
CSS.

- **`ThemeTest` is deleted** with the feature. Eight tests.
- **`WebScaffoldTest`** — `servesHtmxFromTheApplicationRatherThanACdn` and
  `theLayoutReferencesNoExternalHost` must stay green, and the second is the check that the fonts
  really are self-hosted. `everyScreenNamesTheAgencyItIsShowing` must stay green with the agency
  name having moved into the sidebar footer.
- **Screen tests** that assert on markup — `AddPropertyScreenTest`, `AddUnitScreenTest`,
  `UnitScreenTest`, `ReserveScreenTest`, `SearchGroupingTest`, `LeadFormTest` — are updated where
  they assert structure that moved, and **not** where they assert behaviour. An assertion that a
  field exists and is required is behaviour; an assertion about its wrapping element is structure.
  Only the second may be edited.
- **The new tripwire**, per Part 4, with its deliberate red.
- **A gallery test** asserting `/design` renders every fragment — cheap, and it is what catches a
  fragment whose parameters were changed by one caller.
- **`tools/contrast`** is updated to parse `tokens.css` instead of `najem.css`, so it does not
  silently pass by finding no colours.

Two tiers as always: this is all fast-tier. Nothing here needs a container.

## What this spec does not do

Named so nobody reads it as delivered:

- **The Dashboard / Collections screen.** Needs billed, collected, overdue and occupancy queries
  that do not exist.
- **Full fidelity on the six designed screens.** Foundation and reskin only.
- **Responsive behaviour.** The handoff designs ≥1180px and lists responsive as an open question.
  The reskin does not add breakpoints; screens below that width will be uncomfortable, as they are
  in the mockups.
- **The Ledger tab, Financials tab, full property timeline, the three unselected timeline views,
  and the payment-matrix report.** Referenced by the handoff, not drawn.
- **Dark mode, print and PDF output.** Not designed, and dark is now deleted.
- **Fixing the contrast failures.** Recorded above, accepted, not addressed.
