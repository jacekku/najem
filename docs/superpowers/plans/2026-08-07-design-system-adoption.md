# Design System Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's "Kancelaria" appearance with the Najem design handoff, delivered as a reusable component library rather than a rewritten stylesheet.

**Architecture:** Five `@layer`-scoped CSS files (tokens / base / layout / components / screens), a `components.html` of parameterised Thymeleaf fragments, a `/design` gallery that renders every fragment in every state, and a tripwire test that forbids design values in templates. The existing 19 templates then move onto that library.

**Tech Stack:** Java 21, Spring Boot 3.3, Thymeleaf, vendored htmx, plain CSS with `@layer`. No build step, no npm, no framework.

**Spec:** `docs/superpowers/specs/2026-08-07-design-system-adoption-design.md`

**Design reference:** `.designsystem/extracted/design_handoff_najem/` (gitignored). `README.md` is the token and screen spec; `Canvas.dc.html` holds all six screens as inline-styled markup — **open it for values, never copy its markup**. Its `support.js` runtime has already been deleted and must not be reintroduced.

## Global Constraints

- **No third-party runtime assets.** Nothing in a template or stylesheet may reference an external host. `WebScaffoldTest.theLayoutReferencesNoExternalHost` enforces this and must stay green.
- **Polish copy.** Every template is `lang="pl"`. New strings are translated, reusing vocabulary already in the templates. Do not invent statutory terms (`najem okazjonalny`, `wypowiedzenie`, `waloryzacja`) — if a term is not already in the codebase or the handoff, list it for review rather than guessing.
- **Colours only differ; they never decide.** Every status carries words as well as a hue. Do not add a state distinguished by colour alone.
- **Light only.** No `prefers-color-scheme`, no `data-theme`, after Task 4.
- **Palette exactly as drawn.** The handoff's hex values are used verbatim, including the seven that fail WCAG. Do not "fix" a colour.
- **Currency `4 200 zł`** — U+00A0 as thousands separator, `zł` suffix, no decimals when whole. **Dates `dd.mm.rrrr`.**
- **Fast tier only.** Nothing in this plan needs a container. `./gradlew :apps:najem-app:test` is the loop.
- Commit messages: no LLM attribution, author is the current git user.

## File Structure

**Created**
| path | responsibility |
| --- | --- |
| `static/vendor/fonts/*.woff2` | Instrument Sans 400/500/600/700, IBM Plex Mono 400/500/600 — latin **and latin-ext** |
| `static/vendor/fonts/OFL.txt` | licence for both families |
| `static/css/tokens.css` | the handoff's colour table 1:1, type scale, spacing, radii |
| `static/css/base.css` | `@layer` declaration, reset, `@font-face`, element defaults |
| `static/css/layout.css` | shell (216px sidebar + 60px header bar) and the four page grids |
| `static/css/components.css` | every component's styles |
| `static/css/screens.css` | one-offs only |
| `templates/components.html` | parameterised fragments |
| `templates/design.html` | the gallery |
| `web/DesignGalleryController.java` | serves `/design` |
| `test/.../web/DesignGalleryTest.java` | gallery renders |
| `test/.../web/TemplateHygieneTest.java` | the tripwire |

**Deleted**
`static/css/najem.css`, `web/ThemeController.java`, `web/ThemeAdvice.java`, `test/.../web/ThemeTest.java`

**Modified**
`templates/layout.html` (shell), all 15 screen templates, 3 error templates, `tools/contrast`

---

### Task 1: Vendor the fonts, and write tokens + base

**Files:**
- Create: `apps/najem-app/src/main/resources/static/vendor/fonts/` (woff2 files + `OFL.txt`)
- Create: `apps/najem-app/src/main/resources/static/css/tokens.css`
- Create: `apps/najem-app/src/main/resources/static/css/base.css`
- Modify: `apps/najem-app/src/main/resources/static/vendor/README.md`
- Test: `apps/najem-app/src/test/java/pl/najem/app/web/WebScaffoldTest.java` (add one test)

**Interfaces:**
- Produces: the CSS custom properties every later task consumes. Names are fixed here — `--bg-app`, `--bg-surface`, `--bg-subtle`, `--border-default`, `--border-control`, `--border-hairline`, `--border-row`, `--border-row-quiet`, `--text-primary`, `--text-secondary`, `--text-body`, `--text-muted`, `--text-label`, `--text-faint`, `--text-placeholder`, `--text-table-head`, `--text-disabled`, `--accent-green`, `--accent-green-tint`, `--accent-green-tint-2`, `--accent-green-tint-3`, `--accent-green-soft`, `--accent-green-pale`, `--danger`, `--danger-soft`, `--danger-tint`, `--danger-bg`, `--danger-border`, `--warn`, `--warn-soft`, `--warn-tint`, `--warn-bg`, `--warn-border`, `--neutral-pill`, `--neutral-track`, `--neutral-avatar`, `--neutral-chart`, `--neutral-chart-quiet`, `--neutral-icon`, `--neutral-dash`.
- Produces: `--font-sans`, `--font-mono`, `--font-micro`; radii `--r-pill`, `--r-card`, `--r-drop`, `--r-control`, `--r-sm`, `--r-bar`, `--r-gantt`, `--r-chart`; the `@layer` order.

- [ ] **Step 1: Download and vendor the fonts**

Polish needs **latin-ext** (`ł ń ś ż ź ć ę ą ó`). Vendoring only `latin` silently falls back to a system font mid-word, which is the failure this step exists to prevent.

```bash
cd apps/najem-app/src/main/resources/static/vendor
mkdir -p fonts && cd fonts
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
curl -s -A "$UA" "https://fonts.googleapis.com/css2?family=Instrument+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500;600&display=swap" -o /tmp/gf.css

node -e '
const fs=require("fs");
const css=fs.readFileSync("/tmp/gf.css","utf8");
const blocks=css.split("/*").slice(1)
  .map(b=>({subset:b.slice(0,b.indexOf("*/")).trim(), body:b.slice(b.indexOf("*/")+2)}))
  .filter(b=>b.subset==="latin"||b.subset==="latin-ext");
const out=[];
for(const b of blocks){
  const fam=/font-family: *.([^"\x27]+)./.exec(b.body)[1];
  const w=/font-weight: *(\d+)/.exec(b.body)[1];
  const url=/url\((https:[^)]+)\)/.exec(b.body)[1];
  const name=fam.toLowerCase().replace(/ /g,"-")+"-"+w+"-"+b.subset+".woff2";
  out.push({name,url,fam,w,subset:b.subset,range:/unicode-range: *([^;]+);/.exec(b.body)[1]});
}
fs.writeFileSync("/tmp/faces.json",JSON.stringify(out,null,1));
console.log(out.length+" faces");
'
node -e '
const {execSync}=require("child_process");
for(const f of require("/tmp/faces.json")) execSync(`curl -s -o ${f.name} "${f.url}"`);
' 
ls -la *.woff2 | wc -l    # expect 14
```

Expect **14** files (4 Instrument Sans weights + 3 IBM Plex Mono weights, × latin and latin-ext).

Fetch the licence — both families are SIL OFL 1.1:

```bash
curl -s -o OFL.txt https://raw.githubusercontent.com/google/fonts/main/ofl/instrumentsans/OFL.txt
```

Append IBM Plex Mono's licence to the same file with a header naming which family each covers.

- [ ] **Step 2: Write the failing test**

Add to `WebScaffoldTest`:

```java
    /**
     * Polish is not in the latin subset. A vendored font that ships only `latin` renders
     * "Nieruchomości" with the ś and ć from a fallback face — a visible change of typeface
     * mid-word that nobody notices on an English test fixture.
     */
    @Test
    void servesTheFontsItNeedsIncludingPolishGlyphs() throws Exception {
        for (String face : new String[] {
            "instrument-sans-400-latin-ext.woff2",
            "instrument-sans-600-latin-ext.woff2",
            "ibm-plex-mono-400-latin-ext.woff2",
            "ibm-plex-mono-500-latin-ext.woff2"
        }) {
            mvc.perform(get("/vendor/fonts/" + face))
                .andExpect(status().isOk());
        }
    }
```

- [ ] **Step 3: Run it and watch it fail**

```bash
./gradlew :apps:najem-app:test --tests '*WebScaffoldTest*'
```
Expected: FAIL — 404 on each font.

- [ ] **Step 4: Write `tokens.css`**

Every value is copied from the handoff README's token table. Do not adjust any of them.

```css
/*
  The design system's values, and nothing else.

  This file is the one reviewed against the handoff's token table
  (.designsystem/extracted/design_handoff_najem/README.md). A colour that is not in that table
  does not belong here, and a colour used anywhere in the application that is not here is a bug
  TemplateHygieneTest is meant to catch.

  Seven of these fail WCAG AA against --bg-surface and that is a recorded, accepted decision —
  see docs/superpowers/specs/2026-08-07-design-system-adoption-design.md. tools/contrast will
  report them. Do not "fix" one by eye; changing a token is a design decision, not a tidy-up.
*/
@layer tokens {
  :root {
    color-scheme: light;

    /* Surfaces */
    --bg-app: #f6f7f8;
    --bg-surface: #ffffff;
    --bg-subtle: #fbfcfc;

    /* Borders */
    --border-default: #e4e7ea;
    --border-control: #e0e4e8;
    --border-hairline: #eef0f2;
    --border-row: #f2f4f5;
    --border-row-quiet: #f4f6f7;

    /* Text */
    --text-primary: #16191d;
    --text-secondary: #4b5563;
    --text-body: #5c636d;
    --text-muted: #6b7280;
    --text-label: #7b828c;
    --text-faint: #8b929b;
    --text-placeholder: #a8aeb6;
    --text-table-head: #98a0aa;
    --text-disabled: #c3ccd2;

    /* Accent */
    --accent-green: #1f7a5c;
    --accent-green-tint: #eef4f1;
    --accent-green-tint-2: #f0f6f4;
    --accent-green-tint-3: #f6faf8;
    --accent-green-soft: #8fb3a6;
    --accent-green-pale: #e9f2ee;

    /* Status */
    --danger: #b4342a;
    --danger-soft: #d9736a;
    --danger-tint: #fbeceb;
    --danger-bg: #fefaf9;
    --danger-border: #f0d6d3;
    --warn: #a9761c;
    --warn-soft: #e0a83a;
    --warn-tint: #fbf3e2;
    --warn-bg: #fffdf7;
    --warn-border: #f2e6cd;

    /*
      Neutrals. --neutral-pill and --neutral-track are the same hex by coincidence of the
      handoff, which notes they could collapse. They are kept apart because they mean different
      things: one is a pill's background, the other a bar's unfilled track. Collapsing them
      would make a later change to one silently change the other.
    */
    --neutral-pill: #f0f2f4;
    --neutral-track: #eef0f2;
    --neutral-avatar: #e8ecef;
    --neutral-chart: #d6dde1;
    --neutral-chart-quiet: #cdd8d4;
    --neutral-icon: #b6bcc4;
    --neutral-dash: #cfd6dc;

    /* Type */
    --font-sans: "Instrument Sans", system-ui, -apple-system, "Segoe UI", sans-serif;
    --font-mono: "IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    --font-micro: ui-monospace, Menlo, monospace;

    /* Radius */
    --r-pill: 20px;
    --r-card: 10px;
    --r-drop: 9px;
    --r-control: 8px;
    --r-sm: 7px;
    --r-bar: 6px;
    --r-gantt: 5px;
    --r-chart: 3px;

    /* Focus, the one shadow in the system. Cards have none — they are defined by a 1px border. */
    --focus-ring: 0 0 0 3px rgba(31, 122, 92, .1);
  }
}
```

- [ ] **Step 5: Write `base.css`**

The `@layer` statement must come first in load order — put it at the top of this file and load this file first.

```css
/*
  Layer order, declared once for the whole application.

  This is the reason a screen-level rule can never accidentally out-specify a component: later
  layers win regardless of selector specificity, so screens.css never has to reach for
  !important or a longer selector to beat components.css. Add a layer here, not ad hoc.
*/
@layer tokens, base, layout, components, screens;

@layer base {
  /* Seven faces, two subsets each. latin-ext carries ł ń ś ż ź ć ę ą — see the vendor README. */
  @font-face {
    font-family: "Instrument Sans";
    font-style: normal;
    font-weight: 400;
    font-display: swap;
    src: url("/vendor/fonts/instrument-sans-400-latin-ext.woff2") format("woff2");
    unicode-range: U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF, U+0304,
      U+0308, U+0329, U+1D00-1DBF, U+1E00-1E9F, U+1EF2-1EFF, U+2020, U+20A0-20AB, U+20AD-20C0,
      U+2113, U+2C60-2C7F, U+A720-A7FF;
  }
  @font-face {
    font-family: "Instrument Sans";
    font-style: normal;
    font-weight: 400;
    font-display: swap;
    src: url("/vendor/fonts/instrument-sans-400-latin.woff2") format("woff2");
    unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304,
      U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF,
      U+FFFD;
  }
  /* … repeat for Instrument Sans 500/600/700 and IBM Plex Mono 400/500/600, both subsets.
     Copy each unicode-range verbatim from /tmp/faces.json produced in Step 1 — do not retype
     them, and do not merge the two subsets into one rule with a single range. */

  *, *::before, *::after { box-sizing: border-box; }

  html, body { height: 100%; }

  body {
    margin: 0;
    background: var(--bg-app);
    color: var(--text-primary);
    font-family: var(--font-sans);
    font-size: 13px;
    line-height: 1.45;
    -webkit-font-smoothing: antialiased;
  }

  h1, h2, h3, p, figure { margin: 0; }

  a { color: var(--accent-green); text-decoration: none; }
  a:hover { text-decoration: underline; }

  button, input, select, textarea { font: inherit; color: inherit; }

  /* Every money figure, date, id and meter reading is mono so columns align. */
  .mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }

  :focus-visible {
    outline: none;
    border-color: var(--accent-green);
    box-shadow: var(--focus-ring);
  }
}
```

- [ ] **Step 6: Note the fonts in the vendor README**

Append to `static/vendor/README.md`, matching how htmx is documented there: the families, the version pulled, the date, that both `latin` and `latin-ext` are vendored and why, and the OFL requirement to ship the licence.

- [ ] **Step 7: Run the test and watch it pass**

```bash
./gradlew :apps:najem-app:test --tests '*WebScaffoldTest*'
```
Expected: PASS, including `theLayoutReferencesNoExternalHost`.

- [ ] **Step 8: Commit**

```bash
git add apps/najem-app/src/main/resources/static/vendor/fonts \
        apps/najem-app/src/main/resources/static/vendor/README.md \
        apps/najem-app/src/main/resources/static/css/tokens.css \
        apps/najem-app/src/main/resources/static/css/base.css \
        apps/najem-app/src/test/java/pl/najem/app/web/WebScaffoldTest.java
git commit -m "Vendor the design system's fonts and its token layer"
```

---

### Task 2: The component stylesheet and the fragment library

**Files:**
- Create: `apps/najem-app/src/main/resources/static/css/components.css`
- Create: `apps/najem-app/src/main/resources/templates/components.html`

**Interfaces:**
- Consumes: every token from Task 1.
- Produces: the fragment signatures below. Later tasks call these exactly; changing a signature means changing every caller.

```
~{components :: card(title, meta, actions, body, footer)}
~{components :: kpi(label, value, caption, tone)}          tone: '' | 'green' | 'danger'
~{components :: pill(tone, text)}                          tone: danger | warn | neutral | paid
~{components :: chip(text, selected)}
~{components :: btn(href, text, kind)}                     kind: primary | secondary | danger
~{components :: field(name, label, value, type, required, help, suffix)}
~{components :: selectField(name, label, options, selected, required, help)}
~{components :: avatar(initials, size)}
~{components :: keyvalue(label, value, tone, mono)}
~{components :: checklistItem(text, state)}                state: done | required | optional
~{components :: progressBar(segments)}                     list of {pct, tone}
~{components :: railItem(title, subline, date, tone, filled)}
~{components :: microLabel(text)}
~{components :: tabs(items, active)}
~{components :: breadcrumb(items)}
~{components :: emptyState(text)}
~{components :: tableHead(columns, grid)}
```

- [ ] **Step 1: Read the real values out of the mockups**

Before writing a line, open `.designsystem/extracted/design_handoff_najem/Canvas.dc.html` and read the inline styles for each component. Cross-check against the README's "Typography" and "Spacing, radius, shadow" sections. Values that must come out exactly:

| component | values |
| --- | --- |
| card | `background:#fff; border:1px solid var(--border-default); border-radius:var(--r-card)`, padding `15–18px 16–22px`, **no shadow** |
| card title | 13px / 600 |
| KPI label | 11.5px / 500 / `var(--text-label)` |
| KPI value | 25px / 500 / `-.02em` / mono, `margin-top:7px` |
| KPI caption | 11.5px / `var(--text-label)`, `margin-top:5px` |
| table head | `font:600 10px/1 var(--font-micro); letter-spacing:.07em; color:var(--text-table-head)`, uppercase, `padding:0 18px 8px`, `border-bottom:1px solid var(--border-hairline)` |
| table row | `padding:11px 18px` (≈42px), `border-bottom:1px solid var(--border-row)`, `font-size:12.5px` |
| status pill | `border-radius:var(--r-pill); padding:2px 8px; font-size:11px; font-weight:600` |
| secondary button | `border:1px solid var(--border-control); border-radius:var(--r-sm); padding:4px 10px; font-size:11.5px; color:var(--text-secondary)` |
| primary button | `background:var(--accent-green); color:#fff; border-radius:var(--r-control); padding:7px 14px; font-size:12.5px; font-weight:600` |
| input | `border:1px solid var(--border-control); border-radius:var(--r-control); padding:6px 12px; font-size:12.5px` |
| form label | 11.5px / 500 |
| micro label | `9.5–10px / 600`, uppercase, `letter-spacing:.07–.09em`, `var(--text-table-head)`, `var(--font-micro)` |

- [ ] **Step 2: Write `components.css`**

Wrap the whole file in `@layer components { … }`. One section per component with a `/* ── name ── */` rule comment, matching the sectioning style the old `najem.css` used.

Pill tones map: `danger` → `color:var(--danger); background:var(--danger-tint)`; `warn` → `var(--warn)` on `var(--warn-tint)`; `paid` → `var(--accent-green)` on `var(--accent-green-pale)`; `neutral` → `var(--text-muted)` on `var(--neutral-pill)`.

Tables use CSS grid, with the column template passed per table as a custom property so the head and rows cannot disagree:

```css
  .table__head, .table__row { display: grid; grid-template-columns: var(--cols); }
```

Hover, which the mockups only imply: `.table__row:hover { background: var(--bg-subtle); }`.

A card whose body scrolls with head and footer pinned — the spec calls this out as required behaviour on three screens:

```css
  .card--scroll { display: flex; flex-direction: column; min-height: 0; }
  .card--scroll .card__body { flex: 1; min-height: 0; overflow-y: auto; }
  .card--scroll .card__foot { border-top: 1px solid var(--border-hairline); }
```

- [ ] **Step 3: Write `components.html`**

Thymeleaf fragments. Each takes its parameters and emits only its own markup — no fragment resolves data or reaches a service.

```html
<!DOCTYPE html>
<!--/*
  Every component in the design system, once.

  A screen composes these; it does not copy them. That is the whole point — the card's anatomy
  and the 42px table row exist in exactly one place, so screen 30 cannot drift from screen 3 by
  reimplementing them slightly differently.

  Nothing here resolves data or reaches a service. A fragment receives what it renders.
  Every state a fragment supports must appear in design.html, or it is untested.
*/-->
<html xmlns:th="http://www.thymeleaf.org">
<body>

<!--/* A status pill. The text is not optional: the colours only differ, they never decide. */-->
<span th:fragment="pill(tone, text)"
      th:classappend="'pill pill--' + ${tone}" class="pill" th:text="${text}">—</span>

<!--/* A KPI. tone colours only the value — the label and caption stay neutral. */-->
<div th:fragment="kpi(label, value, caption, tone)" class="kpi">
    <div class="kpi__label" th:text="${label}">—</div>
    <div class="kpi__value mono" th:classappend="${tone} ? 'kpi__value--' + ${tone} : ''"
         th:text="${value}">—</div>
    <div class="kpi__caption" th:if="${caption}" th:text="${caption}">—</div>
</div>

<!--/* A card. Any of meta, actions and footer may be null; the card renders without them. */-->
<section th:fragment="card(title, meta, actions, body, footer)" class="card">
    <header class="card__head" th:if="${title}">
        <h2 class="card__title" th:text="${title}">—</h2>
        <span class="card__meta" th:if="${meta}" th:text="${meta}">—</span>
        <div class="card__actions" th:if="${actions}" th:replace="${actions}"></div>
    </header>
    <div class="card__body" th:replace="${body}"></div>
    <footer class="card__foot" th:if="${footer}" th:replace="${footer}"></footer>
</section>

<!--/*
  A form field. `required` renders the asterisk in --danger that the "Still required" checklist
  mirrors; the two must never disagree, so both read the same flag.
  `suffix` is the unit rendered inside the field (zł / mo, m²), not beside it.
*/-->
<div th:fragment="field(name, label, value, type, required, help, suffix)" class="field">
    <label class="field__label" th:for="${name}">
        <span th:text="${label}">—</span><span class="field__req" th:if="${required}">*</span>
    </label>
    <div class="field__wrap">
        <input class="field__input" th:id="${name}" th:name="${name}" th:value="${value}"
               th:type="${type} ?: 'text'" th:required="${required}">
        <span class="field__suffix" th:if="${suffix}" th:text="${suffix}">—</span>
    </div>
    <p class="field__help" th:if="${help}" th:text="${help}">—</p>
</div>

<!--/* … the remaining fragments from the Interfaces block, same shape. */-->

</body>
</html>
```

Write every fragment listed in **Interfaces**. Do not stop at these four.

- [ ] **Step 4: Verify it compiles by rendering**

There is no test yet — Task 3 builds it. Confirm the Thymeleaf syntax parses:

```bash
./gradlew :apps:najem-app:compileJava
```

- [ ] **Step 5: Commit**

```bash
git add apps/najem-app/src/main/resources/static/css/components.css \
        apps/najem-app/src/main/resources/templates/components.html
git commit -m "Add the design system's components as fragments and styles"
```

---

### Task 3: The component gallery at `/design`

**Files:**
- Create: `apps/najem-app/src/main/java/pl/najem/app/web/DesignGalleryController.java`
- Create: `apps/najem-app/src/main/resources/templates/design.html`
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/DesignGalleryTest.java`

**Interfaces:**
- Consumes: every fragment from Task 2.
- Produces: `GET /design` → view `design`.

- [ ] **Step 1: Write the failing test**

```java
package pl.najem.app.web;

/**
 * The gallery renders every component in every state.
 *
 * <p>Cheap, and it catches the one thing a fragment library gets wrong silently: a fragment whose
 * parameters were changed by the screen that needed the change, leaving every other caller
 * rendering an empty element rather than failing. A fragment that is not on this page is not
 * covered by anything.
 *
 * <p>The gallery holds no tenant data and reaches no service, so it needs no workspace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DesignGalleryTest {

    @Autowired MockMvc mvc;

    @Test
    void rendersEveryPillTone() throws Exception {
        mvc.perform(get("/design"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("pill--danger")))
            .andExpect(content().string(containsString("pill--warn")))
            .andExpect(content().string(containsString("pill--neutral")))
            .andExpect(content().string(containsString("pill--paid")));
    }

    @Test
    void rendersTheStatesTheHandoffNeverDrew() throws Exception {
        // Empty, loading and the blocking overlap error are listed as undesigned in the spec.
        // They are designed here, once, rather than improvised per screen.
        mvc.perform(get("/design"))
            .andExpect(content().string(containsString("empty-state")))
            .andExpect(content().string(containsString("field--error")));
    }

    @Test
    void namesEveryFragmentTheLibraryOffers() throws Exception {
        String html = mvc.perform(get("/design")).andReturn().getResponse().getContentAsString();
        for (String fragment : new String[] {
            "card", "kpi", "pill", "chip", "btn", "field", "selectField", "avatar",
            "keyvalue", "checklistItem", "progressBar", "railItem", "microLabel",
            "tabs", "breadcrumb", "emptyState", "tableHead"
        }) {
            assertThat(html).as("gallery must exercise fragment '%s'", fragment)
                .contains("data-fragment=\"" + fragment + "\"");
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :apps:najem-app:test --tests '*DesignGalleryTest*'
```
Expected: FAIL — 404 on `/design`.

- [ ] **Step 3: Write the controller**

```java
package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Every component in the design system, on one page.
 *
 * <p>Two things this buys. The system is visible in one place, so drift is something you see
 * rather than something you discover on screen 20. And the states the handoff never drew — empty,
 * loading, and the blocking overlap error — get designed here once instead of being improvised
 * four times.
 *
 * <p>It takes no workspace because it holds no tenant data: every value on the page is a literal
 * in {@code design.html}. That is also why it is safe for it to be a real route rather than
 * something behind a profile.
 */
@Controller
public class DesignGalleryController {

    @GetMapping("/design")
    public String gallery() {
        return "design";
    }
}
```

- [ ] **Step 4: Write `design.html`**

Sections per component. Every fragment invocation carries `data-fragment="<name>"` on its wrapper so the test above can assert coverage. Every state gets an instance: all four pill tones, the field in default / focus / error / with-suffix / required, the table with rows and empty, the checklist in `done` / `required` / `optional`, the KPI in all three tones, buttons in all three kinds.

The page renders standalone (its own `<head>` linking the five stylesheets) rather than through `layout.html`, so it stays viewable while Task 5 rewrites the shell.

- [ ] **Step 5: Run the tests and watch them pass**

```bash
./gradlew :apps:najem-app:test --tests '*DesignGalleryTest*'
```
Expected: PASS, all three.

- [ ] **Step 6: Commit**

```bash
git add apps/najem-app/src/main/java/pl/najem/app/web/DesignGalleryController.java \
        apps/najem-app/src/main/resources/templates/design.html \
        apps/najem-app/src/test/java/pl/najem/app/web/DesignGalleryTest.java
git commit -m "Show every component in one place at /design"
```

---

### Task 4: Delete dark mode

**Files:**
- Delete: `apps/najem-app/src/main/java/pl/najem/app/web/ThemeController.java`
- Delete: `apps/najem-app/src/main/java/pl/najem/app/web/ThemeAdvice.java`
- Delete: `apps/najem-app/src/test/java/pl/najem/app/web/ThemeTest.java`
- Modify: `templates/layout.html`, `templates/login.html`

- [ ] **Step 1: Confirm nothing else depends on it**

```bash
grep -rn "ThemeAdvice\|themeToOffer\|data-theme\|currentPath" \
  --include=*.java --include=*.html apps modules e2e | grep -v '/build/'
```

Everything returned must be one of the files listed above. `ThemeAdvice.currentPath` is only consumed by the theme form — if anything else reads it, stop and report before deleting.

- [ ] **Step 2: Delete the three files and both toggle forms**

Remove from `layout.html`: the `data-theme` attribute on `<html>`, the `masthead__theme` form, and the two comment blocks explaining server-side theme resolution. Remove from `login.html`: the `data-theme` attribute and the `login__theme` form and its comment.

- [ ] **Step 3: Record why in the spec, not only in the git log**

The decision and its cost are already written in the spec under "Dark mode". Nothing to add — this step is a reminder not to write a fresh apologia into a comment.

- [ ] **Step 4: Run the full app test suite**

```bash
./gradlew :apps:najem-app:test
```
Expected: PASS. `ThemeTest`'s 8 tests are gone; nothing else should have moved.

- [ ] **Step 5: Commit**

```bash
git add -A apps/najem-app
git commit -m "Drop dark mode — the design system is light only"
```

---

### Task 5: The new shell

**Files:**
- Create: `apps/najem-app/src/main/resources/static/css/layout.css`
- Create: `apps/najem-app/src/main/resources/static/css/screens.css` (empty but for its layer wrapper and a comment saying what belongs in it)
- Modify: `apps/najem-app/src/main/resources/templates/layout.html`
- Delete: `apps/najem-app/src/main/resources/static/css/najem.css`
- Modify: `apps/najem-app/src/test/java/pl/najem/app/web/WebScaffoldTest.java`

**Interfaces:**
- Consumes: tokens (Task 1), components (Task 2).
- Produces: the shell every screen renders into — `.shell`, `.sidebar`, `.headerbar`, and the page grids `.grid--main-330`, `.grid--main-300`, `.grid--320-main`, `.grid--steprail`.

- [ ] **Step 1: Write the failing test**

Replace `WebScaffoldTest`'s assertions about the masthead with the new shell's, and add:

```java
    /**
     * The nav has the shape the design gives it, and no item is a link to nothing.
     *
     * <p>Five of the nine destinations are not built. They render muted and non-clickable rather
     * than being omitted, which is a deliberate departure from home.html's "only what is wired is
     * a link" — honoured in substance, since nothing here is a link to nothing.
     */
    @Test
    void theSidebarShowsAllNineDestinationsAndLinksOnlyTheBuiltOnes() throws Exception {
        String html = mvc.perform(get("/").with(anAgency()))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Nieruchomości", "Bank", "Zaległości");
        // Unbuilt: rendered, muted, and carrying no href.
        assertThat(html).contains("nav__item--unbuilt");
        assertThat(html).doesNotContain("href=\"/tenancies\"");
    }

    /**
     * A manager who has just created something must be able to tell "not yet" from "it didn't
     * work". The mockups have no home for this badge; dropping it would have been the quiet
     * option, and it is the condition on which an eventually consistent board was accepted.
     */
    @Test
    void theHeaderBarStillSaysWhenTheViewIsBehind() throws Exception {
        // … render with projectionsBehind true and assert the badge is present
    }
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :apps:najem-app:test --tests '*WebScaffoldTest*'
```
Expected: FAIL — no `nav__item--unbuilt`.

- [ ] **Step 3: Write `layout.css`**

Wrap in `@layer layout`. From the handoff: sidebar `width:216px; flex:none; background:var(--bg-surface); border-right:1px solid var(--border-default); display:flex; flex-direction:column; padding:20px 0`. Logo lockup `padding:0 20px 22px`, 26px `var(--accent-green)` square at `var(--r-sm)`, white bold 13px "N", wordmark 15px/600/`-.01em`. Nav items `padding:8px 10px; border-radius:var(--r-sm); gap:10px; font-size:13px`; active `background:var(--accent-green-tint); color:var(--accent-green); font-weight:600`; idle `color:var(--text-secondary)`; unbuilt `color:var(--text-disabled)` with `cursor:default`. Footer `margin-top:auto`.

Header bar: `height:60px; border-bottom:1px solid var(--border-default); background:var(--bg-surface)`. Content area `padding:22px 26px; display:flex; flex-direction:column; gap:18px`.

The four page grids, named because every screen is one of them:

```css
  .grid--main-330 { display: grid; grid-template-columns: 1fr 330px; gap: 16px; min-height: 0; }
  .grid--main-300 { display: grid; grid-template-columns: 1fr 300px; gap: 16px; min-height: 0; }
  .grid--320-main { display: grid; grid-template-columns: 320px 1fr; gap: 16px; min-height: 0; }
  .grid--steprail { display: flex; }
```

- [ ] **Step 4: Rewrite `layout.html`**

Sidebar + header bar per the spec's mapping table. Keep the two comment blocks that carry live reasoning — the htmx-not-from-a-CDN paragraph and the `projectionsBehind` paragraph — and delete the theme ones (already gone in Task 4). Link the five stylesheets in layer order:

```html
    <link rel="stylesheet" th:href="@{/css/tokens.css}">
    <link rel="stylesheet" th:href="@{/css/base.css}">
    <link rel="stylesheet" th:href="@{/css/layout.css}">
    <link rel="stylesheet" th:href="@{/css/components.css}">
    <link rel="stylesheet" th:href="@{/css/screens.css}">
```

The nine nav items, in the handoff's two groups, with the `ACCOUNTING` micro-label between them at `padding:20px 22px 8px`:

| item | Polish | route |
| --- | --- | --- |
| Dashboard | Pulpit | `/` |
| Properties | Nieruchomości | `/properties` |
| Tenancies | Najmy | — unbuilt |
| Payments | Płatności | — unbuilt |
| *ACCOUNTING* | *KSIĘGOWOŚĆ* | micro-label |
| Invoices | Faktury | — unbuilt |
| Bank feed | Bank | `/bank` |
| General ledger | Księga główna | — unbuilt |
| Owner statements | Rozliczenia właścicieli | — unbuilt |
| Reports | Zaległości | `/report` |

`Zaległości` is the existing screen's own word and is kept rather than translated back to "Raporty".

- [ ] **Step 5: Delete `najem.css` and update `home.html`'s comment**

`home.html` states *"Only what is wired is a link."* Update it to say what is now true: nothing is a link to nothing, and unbuilt destinations render muted in the sidebar. Leaving it would have it contradict the nav beside it.

- [ ] **Step 6: Run the full suite**

```bash
./gradlew :apps:najem-app:test
```
Expected: PASS. Screens will look rough inside the new shell — that is Tasks 6 and 7.

- [ ] **Step 7: Commit**

```bash
git add -A apps/najem-app
git commit -m "Replace the masthead with the design system's sidebar and header bar"
```

---

### Task 6: Reskin the nine structural templates

**Files:** `properties.html`, `units.html`, `unit.html`, `property-new.html`, `unit-new.html`, `reserve-parties.html`, `reserve-terms.html`, `report.html`, `timeline.html`
**Test:** `AddPropertyScreenTest`, `AddUnitScreenTest`, `UnitScreenTest`, `ReserveScreenTest`, `LeadFormTest`

**Interfaces:** Consumes every fragment from Task 2 and the grids from Task 5.

- [ ] **Step 1: Establish the rule for editing existing tests**

Before touching a template, read the test that covers it. The rule, applied per assertion:

- **Behaviour — do not change.** "This field exists", "it is required", "this value renders", "posting this returns 302".
- **Structure — may change.** "It is inside a `<table>`", "the class is `unit-row`".

If making a test pass requires changing an assertion in the first group, the reskin has broken behaviour. Stop and report rather than editing the assertion.

- [ ] **Step 2: Reskin, one template per commit**

For each, in this order — `properties`, `units`, `unit`, `report`, `timeline`, `property-new`, `unit-new`, `reserve-parties`, `reserve-terms`:

1. Read the corresponding screen in `Canvas.dc.html` for layout and values.
2. Replace hand-written markup with fragment calls. A `<table>` becomes `tableHead` + `.table__row`; a heading block becomes breadcrumb + title row + `tabs`; a form becomes `field` calls.
3. Choose the page grid: detail screens `grid--main-300`, dashboard-like `grid--main-330`, unit detail `grid--320-main`, wizards `grid--steprail`.
4. Run that screen's test.
5. Commit.

**Fidelity is bounded here.** `unit.html` takes the *Unit + tenancy detail* header, tab strip and card anatomy; its tenancy band, ledger tab and attention rail have no data behind them and are **not** built. Same for `units.html` against *Property detail* — no NOI sparkline, no owner rail. Building those is separate work. Do not invent data to fill a component.

- [ ] **Step 3: Run the full suite**

```bash
./gradlew :apps:najem-app:test
```
Expected: PASS.

---

### Task 7: Reskin the chrome-only templates

**Files:** `home.html`, `agencies.html`, `search.html`, `bank.html`, `parties-form.html`, `error/forbidden.html`, `error/no-agency.html`, `error/not-invited.html`, `login.html`

- [ ] **Step 1: Reskin the eight in-shell templates**

Shell, cards, tables and buttons only — none of these has a designed counterpart, so nothing is invented. `home.html` stays four destinations, restyled; the Dashboard is separate work.

- [ ] **Step 2: Reskin `login.html`**

Outside the sidebar shell — it renders before there is an agency. Its own centred layout on the tokens and field components.

- [ ] **Step 3: Run the full suite and commit**

```bash
./gradlew :apps:najem-app:test
git add -A apps/najem-app && git commit -m "Move the remaining screens onto the design system"
```

---

### Task 8: The tripwire

**Files:**
- Create: `apps/najem-app/src/test/java/pl/najem/app/web/TemplateHygieneTest.java`

- [ ] **Step 1: Write the test**

```java
/**
 * No template carries a design value.
 *
 * <p>A hex colour, a style attribute or a hardcoded px in a template is the design system
 * eroding one screen at a time — each instance individually reasonable, and collectively the
 * reason a token file stops describing the application. The values live in css/, once.
 *
 * <p><b>When this goes red, fix the template. Do not widen the regex.</b> The person who hits
 * this is under pressure to make it green and widening is the fast way; it is also how a
 * two-branch check degrades into a check of nothing (refactoring.md rules 20 and 21).
 *
 * <p>One exemption, and it is narrow: an inline SVG's own geometry — viewBox, width, height, d,
 * stroke-width — is the icon's shape and cannot move to CSS. It does not extend to fill or
 * stroke taking a hex; icons inherit currentColor.
 */
@Test
void noTemplateCarriesADesignValue() throws Exception { /* walk templates/, assert */ }
```

Scan every `.html` under `src/main/resources/templates`. Flag `#[0-9a-fA-F]{3,8}` outside an `<svg>`, any `style="`, and `\d+px` outside an `<svg>`.

- [ ] **Step 2: Run it**

Expected: PASS if Tasks 6 and 7 were done properly. **If it fails, fix the templates** — that is the test working.

- [ ] **Step 3: Break it deliberately, and watch it go red**

Rule 21: a tripwire that has never been seen to fail is a comment.

```bash
# add style="color:#b4342a" to any template, run, confirm RED, then revert
./gradlew :apps:najem-app:test --tests '*TemplateHygieneTest*'
git checkout -- <that template>
```

Record in the commit message that it was seen red.

- [ ] **Step 4: Commit**

```bash
git add apps/najem-app/src/test/java/pl/najem/app/web/TemplateHygieneTest.java
git commit -m "Refuse a design value in a template"
```

---

### Task 9: Point the contrast checker at the new tokens, and verify visually

**Files:** `tools/contrast`

- [ ] **Step 1: Repoint `tools/contrast`**

It parses `najem.css`, which no longer exists — so it currently finds no colours and passes vacuously, which is worse than failing. Point it at `static/css/tokens.css` and update its docstring: it is no longer a gate, the seven sub-AA values are a recorded decision, and its job now is to catch a *new* value that fails.

- [ ] **Step 2: Run it and record the output**

```bash
tools/contrast || true
```

Expected: reports the seven known values from the spec's table, and nothing else. **An eighth is a bug introduced by this work.**

- [ ] **Step 3: Boot the app**

```bash
docker compose up -d
./gradlew :apps:fakebank:bootRun &
./gradlew :apps:najem-app:bootRun --args="\
  --najem.security.permit-all=true \
  --najem.bootstrap.operator-subject=00000000-0000-0000-0000-000000000001 \
  --najem.bank.fake.enabled=true \
  --najem.bank.base-url=http://localhost:8081"
NAJEM_OPERATOR_SUBJECT=00000000-0000-0000-0000-000000000001 ./tools/seed-demo.sh
```

- [ ] **Step 4: Screenshot every screen with Playwright at 1400×920**

`/design` first — it is the whole system on one page and the fastest way to spot a broken component. Then `/`, `/properties`, a property's units, a unit, `/report`, `/timeline`, `/bank`, `/search?q=`, `/agencies`, both wizards, and `/login`.

For each: compare against the corresponding screen in `Canvas.dc.html`, and check the browser console is clean — **a 404 on a font is the failure most likely to pass every test and still be wrong**, because the fallback face renders perfectly readable Polish.

- [ ] **Step 5: Report**

List every screen with its screenshot, any divergence from the mockups, the contrast output, and the full test result. Do not claim done without the test output in hand.

- [ ] **Step 6: Commit**

```bash
git add tools/contrast && git commit -m "Point the contrast checker at the new token file"
```

---

## Self-Review

**Spec coverage.** Fonts self-hosted → T1. Tokens → T1. Layered CSS → T1/T2/T5. Fragments → T2. Gallery and the undesigned states → T3. Dark mode deleted → T4. Shell and the nine nav items → T5. `home.html`'s comment → T5 Step 5. Structural reskin → T6. Chrome-only reskin → T7. Tripwire with its deliberate red → T8. `tools/contrast` → T9. Polish copy → Global Constraints and T5's translation table. Out-of-scope items are listed in the spec and no task claims them.

**Known gap, deliberate.** The spec's caution about `ThemeController`'s open-redirect hardening being lost with the file is *not* actioned — T4 deletes it. Raised for the user in review; no other code takes a user-supplied redirect target today.

**Type consistency.** Fragment signatures are declared once in T2's Interfaces block and referenced by name in T3, T6 and T7. Token names are declared once in T1's Interfaces block. Grid class names are declared in T5 and used in T6.
