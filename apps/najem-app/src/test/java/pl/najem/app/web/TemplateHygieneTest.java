package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three source scans over every template, run without Spring and without a database.
 *
 * <p>All three defects are invisible to a rendered-page check: a stray {@code style=}, a dead
 * {@code th:if} guard and a mangled {@code __} expression either render something that looks
 * plausible or fail with an error that names nothing recognisable, so review and a green test
 * suite both miss them. A source scan catches the twelfth instance nobody has typed yet the
 * moment it is typed, the same reasoning {@code WorkspaceBoundaryTest} (modules:propertymanagement)
 * already relies on for a different class of defect.
 *
 * <p>Two explicit roots, never one glob from a shared parent: {@link #TEMPLATES} is the real
 * template tree this application serves, {@link #FIXTURES} is a committed set of deliberately
 * broken specimens that exist only to prove each tripwire still bites. Naming them separately is
 * what keeps a fixture from ever being counted as a real offender, and — the direction that
 * matters more — what keeps a real template from ever hiding inside "that's just a fixture".
 *
 * <p>Every tripwire below asserts both halves in the same {@code @Test}: the real tree is clean,
 * and the fixture built for that tripwire is still caught. A scanner that returns no offenders is
 * indistinguishable from a scanner that is broken (refactoring.md rule 20) unless something it is
 * known to have to catch is still in the tree for it to catch.
 */
class TemplateHygieneTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path FIXTURES = Path.of("src/test/resources/hygiene-fixtures");

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern SVG_ELEMENT =
        Pattern.compile("<svg\\b[^>]*>.*?</svg>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("([\\w:.-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern HEX_COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
    private static final Pattern HARDCODED_PX = Pattern.compile("\\d+px");
    /**
     * One element's opening tag, INCLUDING one whose attribute value contains {@code <} or
     * {@code >}.
     *
     * <p>This was {@code <[a-zA-Z][^<>]*>}, which cannot match such an element at all: the character
     * class stops dead at the {@code <} inside the attribute and there is no {@code >} before it to
     * close on, so the whole tag is skipped and Tripwire B never scans it. Two elements in the real
     * tree already qualified — {@code components.html}'s {@code stepRail}, whose {@code
     * th:classappend} and {@code th:text} both compare {@code iter.count < current} — which means the
     * scanner reported a clean tree while silently declining to look at two of its elements. A
     * scanner that skips is worse than a scanner that fails, because skipping reads as a pass.
     *
     * <p>The fix consumes a double-quoted attribute value as a unit, so {@code <} and {@code >} are
     * ordinary characters once inside quotes and only an unquoted {@code >} ends the tag. Note this
     * is deliberately NOT a widening of what counts as a violation — Tripwire B's own instruction is
     * "fix the template, do not widen the regex", and this changes what the scanner can SEE rather
     * than what it accepts. The two elements it newly sees are clean; the point is that they are now
     * looked at.
     *
     * <p>Single quotes are not handled, because HTML attributes in this tree are double-quoted
     * throughout and treating {@code '} as a delimiter would break every SpEL string literal
     * ({@code th:case="'GOLDEN'"}) instead.
     */
    private static final Pattern TAG =
        Pattern.compile("<[a-zA-Z](?:[^<>\"]|\"[^\"]*\")*>", Pattern.DOTALL);
    private static final Pattern TH_ATTRIBUTE =
        Pattern.compile("\\b(th:[\\w-]+)\\s*=\\s*\"([^\"]*)\"");

    /** An inline {@code <svg>}'s own shape — cannot move to a class, so it is exempt from A. */
    private static final Set<String> SVG_GEOMETRY_ATTRIBUTES =
        Set.of("viewBox", "d", "width", "height", "stroke-width");

    // ------------------------------------------------------------------------------------------
    // Tripwire A — no design value in a template
    // ------------------------------------------------------------------------------------------

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
     * <p>Scoped to attribute values only, deliberately: {@code components.html} has {@code px} in
     * comment prose and {@code design.html} has the literal text {@code 1180px} as visible copy
     * in its "not designed" list, and both are legitimate — this scan never looks at a comment or
     * a text node, only at what is between the quotes of an {@code attr="…"}.
     *
     * <p>Two narrow exemptions. First, an inline {@code <svg>}'s own geometry —
     * {@code viewBox}, {@code d}, {@code width}, {@code height}, {@code stroke-width} — is the
     * icon's shape and cannot move to CSS; it does not extend to {@code fill} or {@code stroke}
     * taking a hex, since icons inherit {@code currentColor}. Second, {@code th:style} driven
     * purely by runtime data is allowed — {@code components.html}'s {@code progressBar} computes
     * a percentage from real money that no class could express — but a literal px inside a
     * {@code th:style} is still a defect; nothing here special-cases the attribute name, so a
     * hardcoded value in one is caught exactly like a hardcoded value in a literal {@code style=}.
     */
    @Test
    void noTemplateCarriesADesignValue() throws IOException {
        List<Violation> real = htmlFiles(TEMPLATES)
            .flatMap(file -> designValueViolations(file).stream())
            .toList();

        assertThat(real)
            .as("a template must not carry a design value directly — move it to a token or a "
                + "class in css/, per the file:line named above")
            .isEmpty();

        List<Violation> fixture = designValueViolations(FIXTURES.resolve("bad-hex-in-attribute.html"));

        assertThat(fixture)
            .as("bad-hex-in-attribute.html must still trip this tripwire — a scanner that finds "
                + "nothing here has silently stopped matching, which is indistinguishable from a "
                + "clean tree unless this fixture is still here to be caught (refactoring.md rule 20)")
            .isNotEmpty();
    }

    private static List<Violation> designValueViolations(Path file) {
        List<Violation> violations = new ArrayList<>();
        String content = read(file);
        List<int[]> comments = spans(COMMENT.matcher(content));
        List<int[]> svgSpans = spans(SVG_ELEMENT.matcher(content));

        Matcher attribute = ATTRIBUTE.matcher(content);
        while (attribute.find()) {
            if (within(comments, attribute.start())) {
                continue;
            }
            String name = attribute.group(1);
            String value = attribute.group(2);
            int line = lineOf(content, attribute.start());
            boolean exemptGeometry =
                within(svgSpans, attribute.start()) && SVG_GEOMETRY_ATTRIBUTES.contains(name);

            if (name.equals("style")) {
                violations.add(new Violation(file, line,
                    "literal style=\"" + value + "\" — a class in css/ carries this, never the template"));
            }
            if (!exemptGeometry && HEX_COLOUR.matcher(value).find()) {
                violations.add(new Violation(file, line,
                    "hex colour in " + name + "=\"" + value + "\" — belongs in a token"));
            }
            if (!exemptGeometry && HARDCODED_PX.matcher(value).find()) {
                violations.add(new Violation(file, line,
                    "hardcoded px in " + name + "=\"" + value + "\" — belongs in a token or a class"));
            }
        }
        return violations;
    }

    // ------------------------------------------------------------------------------------------
    // Tripwire B — th:replace and th:if/th:unless never share an element
    // ------------------------------------------------------------------------------------------

    /**
     * No element carries both {@code th:replace} and {@code th:if}/{@code th:unless}.
     *
     * <p>This is always a bug and it is invisible in review. Thymeleaf evaluates fragment
     * inclusion at precedence 100 and conditionals at 300, so the element is substituted
     * <em>before</em> the condition is ever read — the guard silently does nothing, and the
     * fragment renders regardless of what the condition says. It cost this migration a round:
     * twelve instances across eight templates rendered empty states on top of populated tables,
     * and a full green test suite did not notice, because the rendered output was valid HTML
     * either way. The fix is always the same shape: put the condition on a wrapping element and
     * the replace on a child, as every real template here now does — see units.html's own note
     * beside its emptyState.
     *
     * <p><b>When this goes red, fix the template. Do not widen the regex.</b> Splitting the
     * condition and the fragment onto two elements is the only correct fix; there is no reading
     * of "th:if and th:replace on one element" that is ever intended.
     *
     * <p>This deliberately does not match {@code th:insert}. {@code th:replace} discards the host
     * element and substitutes the fragment in its place, taking any unevaluated {@code th:if} on
     * that element down with it; {@code th:insert} keeps the host tag — the fragment becomes its
     * child — so a guard on that same element still runs before the fragment is included. Proven,
     * not just reasoned: {@code reserve-parties.html:68} and {@code :94} both carry {@code th:if}
     * on the same element as {@code th:insert}, and rendering
     * {@code /units/{id}/reserve?interestId=…&role=tenant} versus {@code role=guarantor} shows the
     * {@code givenName} input exactly once in each case, not twice. A dead guard would render both
     * the tenant and guarantor pickers together. Widening this tripwire to also flag
     * {@code th:insert} would turn those two lines into false positives on a pattern that is fine.
     */
    @Test
    void noElementCarriesBothThReplaceAndAGuard() throws IOException {
        List<Violation> real = htmlFiles(TEMPLATES)
            .flatMap(file -> deadGuardViolations(file).stream())
            .toList();

        assertThat(real)
            .as("th:replace substitutes the element before th:if/th:unless is ever read — the "
                + "guard at the file:line above does nothing; split it onto a wrapping element")
            .isEmpty();

        List<Violation> fixture = deadGuardViolations(FIXTURES.resolve("bad-dead-guard.html"));

        assertThat(fixture)
            .as("bad-dead-guard.html must still trip this tripwire — see the rationale on "
                + "noTemplateCarriesADesignValue's fixture assertion, same failure mode")
            .isNotEmpty();

        // Both specimens, counted, not merely "not empty". The fixture's second element carries '<'
        // inside an attribute value, which the previous TAG pattern could not match at all — so it
        // was invisible to this scanner while the first specimen kept the assertion above green.
        // Asserting two is what makes narrowing TAG back go red here instead of nowhere; see TAG's
        // own javadoc for the two real elements this hole was skipping.
        assertThat(fixture)
            .as("both of bad-dead-guard.html's specimens must be found — the second has '<' inside "
                + "an attribute value, and a TAG pattern that cannot cross it silently skips the "
                + "whole element rather than failing (refactoring.md rules 20 and 21)")
            .hasSize(2);
    }

    private static List<Violation> deadGuardViolations(Path file) {
        List<Violation> violations = new ArrayList<>();
        String content = read(file);
        List<int[]> comments = spans(COMMENT.matcher(content));

        Matcher tag = TAG.matcher(content);
        while (tag.find()) {
            if (within(comments, tag.start())) {
                continue;
            }
            String source = tag.group();
            boolean hasReplace = source.contains("th:replace=");
            boolean hasGuard = source.contains("th:if=") || source.contains("th:unless=");
            if (hasReplace && hasGuard) {
                violations.add(new Violation(file, lineOf(content, tag.start()),
                    "th:replace and a guard on one element — the fragment always renders: "
                        + condensed(source)));
            }
        }
        return violations;
    }

    // ------------------------------------------------------------------------------------------
    // Tripwire C — at most one "__" inside any single th:* expression
    // ------------------------------------------------------------------------------------------

    /**
     * No {@code th:*} attribute's expression contains more than one {@code __}.
     *
     * <p>Thymeleaf treats a matched {@code __…__} pair as <b>preprocessing</b> delimiters:
     * everything between them is evaluated and substituted into the expression before the
     * expression itself is parsed. One {@code __} in an expression is harmless — there is nothing
     * to pair it with — which is why {@code 'kpi__value--' + tone}, {@code 'tabs__item--active'},
     * {@code 'nav__item--active'} and every other single-BEM-modifier expression in this codebase
     * is safe. The second {@code __} in the same expression is what breaks it: the two pair up,
     * everything between them is preprocessed, and the expression comes apart with a parse error
     * that quotes a string appearing nowhere in the visible source. This exact shape took down 24
     * tests from one line in {@code components.html}'s {@code stepRail} fragment, fixed by
     * switching to plain {@code is-active}/{@code is-done} state classes — see that fragment's own
     * comment for the full account.
     *
     * <p><b>When this goes red, fix the template. Do not widen the regex.</b> The fix is never to
     * accept a second {@code __}; it is to express the second modifier a different way — a plain
     * state class, a second attribute, or a helper that returns the whole class string already
     * assembled.
     */
    @Test
    void noThymeleafExpressionCarriesTwoDoubleUnderscores() throws IOException {
        List<Violation> real = htmlFiles(TEMPLATES)
            .flatMap(file -> doubleUnderscoreViolations(file).stream())
            .toList();

        assertThat(real)
            .as("two '__' in one th:* expression are preprocessing delimiters that pair up and "
                + "mangle the expression — see the file:line above; express the second modifier "
                + "a different way")
            .isEmpty();

        List<Violation> fixture = doubleUnderscoreViolations(FIXTURES.resolve("bad-double-underscore.html"));

        assertThat(fixture)
            .as("bad-double-underscore.html must still trip this tripwire — see the rationale on "
                + "noTemplateCarriesADesignValue's fixture assertion, same failure mode")
            .isNotEmpty();
    }

    private static List<Violation> doubleUnderscoreViolations(Path file) {
        List<Violation> violations = new ArrayList<>();
        String content = read(file);
        List<int[]> comments = spans(COMMENT.matcher(content));

        Matcher attribute = TH_ATTRIBUTE.matcher(content);
        while (attribute.find()) {
            if (within(comments, attribute.start())) {
                continue;
            }
            String name = attribute.group(1);
            String value = attribute.group(2);
            int count = countOccurrences(value, "__");
            if (count >= 2) {
                violations.add(new Violation(file, lineOf(content, attribute.start()),
                    count + " occurrences of '__' in " + name + "=\"" + value + "\" — the second "
                        + "pairs with the first and Thymeleaf preprocesses everything between them"));
            }
        }
        return violations;
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int from = 0;
        int hit;
        while ((hit = value.indexOf(needle, from)) >= 0) {
            count++;
            from = hit + needle.length();
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------
    // Tripwire D — every class used in a template has a rule in the stylesheets
    // ------------------------------------------------------------------------------------------

    private static final Path STYLESHEETS = Path.of("src/main/resources/static/css");
    private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("\\bclass\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern CSS_CLASS_SELECTOR = Pattern.compile("\\.([a-zA-Z_-][\\w-]*)");

    /**
     * Two names a used-class sweep finds that are not real violations — not a silent ignore-list,
     * because a bare list rots the moment the reason it was written for stops applying. Each entry
     * below carries its own reason so a future reader can tell whether it still holds.
     */
    private static final Set<String> ALLOWED_CLASSLESS = Set.of(
        // Not a class at all: a concatenation prefix in components.html kpi's th:classappend
        // ('kpi__value--' + tone), completed at runtime with a tone suffix — 'kpi__value--danger'
        // and 'kpi__value--green' are the real, styled classes. This scan matches class="..." by
        // regex without excluding HTML comments (unlike Tripwires A-C), and components.html's own
        // comment just above that line quotes the literal string class="kpi__value--" while
        // describing the bug that string used to be, so the token is picked up from prose, not
        // from any element's actual class attribute.
        "kpi__value--",
        // A real class, deliberately unstyled: components.html's keyvalue fragment applies it to
        // every value cell, and it is meant to inherit the surrounding body colour rather than
        // carry a rule of its own. Only its '--danger' modifier and its 'mono' sibling class are
        // styled.
        "keyvalue__value"
    );

    /**
     * Every class named in a template's own {@code class="..."} attributes has at least one rule
     * in the stylesheets that style it.
     *
     * <p>A class with no rule produces no error, no failing test and no obviously-wrong
     * screenshot — it just quietly renders unstyled. Six such classes were found by hand on this
     * branch before this tripwire existed, two of which meant every form in the app had zero gap
     * between its stacked fields.
     *
     * <p>Deliberately narrower than it could be: this scans only the literal {@code class="..."}
     * attribute, not {@code th:classappend} — a value built at runtime from a caller's argument
     * (a tone, tenant data) is not a class name this file can name in advance, so there is nothing
     * literal here to check it against. Every class {@code th:classappend} can produce is either
     * already covered by a caller's own literal {@code class="..."} (e.g. {@code kpi__value}
     * itself) or is a modifier suffix that has to be read from the CSS source directly to confirm,
     * the way {@link #ALLOWED_CLASSLESS}'s own comment for {@code kpi__value--} had to be.
     *
     * <p><b>When this goes red, fix the template.</b> Either the class is a typo for one that
     * exists, or the stylesheet is missing a rule for it — not a case for widening
     * {@link #ALLOWED_CLASSLESS} without a reason that will still make sense in a year.
     */
    @Test
    void everyTemplateClassHasAStylesheetRule() throws IOException {
        Set<String> defined = cssClassSelectors();

        List<Violation> real = htmlFiles(TEMPLATES)
            .flatMap(file -> unstyledClassViolations(file, defined).stream())
            .toList();

        assertThat(real)
            .as("a class used in a template must carry at least one rule under css/, or it "
                + "renders unstyled with no error to say so — see the file:line above, and check "
                + "ALLOWED_CLASSLESS before assuming the class needs a new rule instead")
            .isEmpty();

        List<Violation> fixture =
            unstyledClassViolations(FIXTURES.resolve("bad-unstyled-class.html"), defined);

        assertThat(fixture)
            .as("bad-unstyled-class.html must still trip this tripwire — see the rationale on "
                + "noTemplateCarriesADesignValue's fixture assertion, same failure mode")
            .isNotEmpty();
    }

    private static List<Violation> unstyledClassViolations(Path file, Set<String> defined) {
        List<Violation> violations = new ArrayList<>();
        String content = read(file);

        Matcher attribute = CLASS_ATTRIBUTE.matcher(content);
        while (attribute.find()) {
            String value = attribute.group(1);
            if (value.contains("$") || value.contains("{")) {
                continue;
            }
            int line = lineOf(content, attribute.start());
            for (String token : value.split("\\s+")) {
                if (token.isBlank() || ALLOWED_CLASSLESS.contains(token) || defined.contains(token)) {
                    continue;
                }
                violations.add(new Violation(file, line,
                    "class=\"" + token + "\" has no rule in any stylesheet under css/ — it "
                        + "renders unstyled"));
            }
        }
        return violations;
    }

    /** Every class selector named in any stylesheet, comments stripped so a class mentioned only
     *  in CSS prose is never mistaken for a defined one. */
    private static Set<String> cssClassSelectors() throws IOException {
        assertThat(Files.isDirectory(STYLESHEETS))
            .as("%s not found — this scan must fail loudly rather than silently cover nothing",
                STYLESHEETS)
            .isTrue();
        Set<String> selectors = new java.util.HashSet<>();
        List<Path> files;
        try (var walk = Files.walk(STYLESHEETS)) {
            files = walk.filter(path -> path.toString().endsWith(".css")).sorted().toList();
        }
        assertThat(files).as("no stylesheets found under %s", STYLESHEETS).isNotEmpty();
        for (Path file : files) {
            String content = CSS_COMMENT.matcher(read(file)).replaceAll("");
            Matcher selector = CSS_CLASS_SELECTOR.matcher(content);
            while (selector.find()) {
                selectors.add(selector.group(1));
            }
        }
        return selectors;
    }

    // ------------------------------------------------------------------------------------------
    // Shared scanning machinery
    // ------------------------------------------------------------------------------------------

    /** One offence, named precisely enough to go straight to the line without re-deriving it. */
    private record Violation(Path file, int line, String detail) {

        @Override
        public String toString() {
            return file + ":" + line + " — " + detail;
        }
    }

    /**
     * Every {@code .html} under {@code root}, walked rather than listed by hand so a new template
     * is covered the moment it is added. Fails loudly rather than passing on an empty list — a
     * root that resolved to nothing would otherwise look identical to a clean tree.
     */
    private static Stream<Path> htmlFiles(Path root) throws IOException {
        assertThat(Files.isDirectory(root))
            .as("%s not found — this scan must fail loudly rather than silently cover nothing", root)
            .isTrue();
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(path -> path.toString().endsWith(".html")).sorted().toList();
        }
        assertThat(files).as("no templates found under %s", root).isNotEmpty();
        return files.stream();
    }

    private static List<int[]> spans(Matcher matcher) {
        List<int[]> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        return spans;
    }

    private static boolean within(List<int[]> spans, int index) {
        for (int[] span : spans) {
            if (index >= span[0] && index < span[1]) {
                return true;
            }
        }
        return false;
    }

    private static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Collapses a multi-line tag to one line and caps its length for a readable failure message. */
    private static String condensed(String tag) {
        String flattened = tag.replaceAll("\\s+", " ").trim();
        return flattened.length() > 160 ? flattened.substring(0, 160) + "…" : flattened;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
