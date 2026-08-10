package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The responsive handoff's breakpoint matrix, pinned value by value.
 *
 * <p>The spec (`.designsystem/Najem-responsive-spec.html`, "Breakpoints") is a table: three stops —
 * ≥1180 as drawn, 1024–1179, and 768–1023 — and for each one a sidebar width, a page gutter, a card
 * padding, a KPI arrangement and a tab strip. Every cell of it is implemented. This test is that
 * table written a second time, in the one place that fails when the stylesheet stops agreeing with
 * it.
 *
 * <p><b>Why a test and not the comments that are already there.</b> layout.css and components.css
 * explain each stop at length and quote the spec while doing it, and that prose is worth keeping —
 * but a comment cannot notice when the declaration beneath it changes. These are plain numbers with
 * no visible consequence at the width anybody develops at: a gutter that drifts from 16px to 18px at
 * 768, or a sidebar that goes back to 180px, looks like nothing on a 1440px laptop and is only ever
 * seen by whoever is holding a tablet. This has already happened once: the first pass at these
 * breakpoints collapsed the sidebar at the wrong stop and stacked the rails at the wrong one, and
 * both survived review because reviewing happens at desk width.
 *
 * <p><b>Scanning the stylesheet, not a rendered page.</b> There is no browser in this suite, so this
 * reads the cascade as source: the declaration a selector carries inside a given {@code @media}
 * context. That is weaker than measuring a real layout — it cannot catch a rule defeated by
 * specificity from somewhere else — and it is deliberately paired with the sibling that covers the
 * one way that happens here: {@link StylesheetHygieneTest#noMediaQuerySitsOutsideALayer()} is what
 * stops an unlayered {@code @media} silently outranking everything this asserts.
 *
 * <p><b>When this goes red, the question is which is wrong — the stylesheet or the spec.</b> If the
 * handoff changed, change the matrix below and say so in the commit; the numbers here have no
 * authority of their own, they are a copy. What must not happen is the assertion being relaxed to
 * whatever the stylesheet now says, which turns the copy into a mirror and the test into a
 * tautology.
 */
class ResponsiveSpecTest {

    private static final Path CSS = Path.of("src/main/resources/static/css");
    private static final Path FIXTURES = Path.of("src/test/resources/hygiene-fixtures/css");

    /** The base stop, ≥1180 — the width the handoff draws at, expressed with no media query. */
    private static final int AS_DRAWN = 0;

    /**
     * The spec's table, one row per cell that has a number in it.
     *
     * <p>Ordered as the spec orders it: the stop, then the thing that changes. A row reads "at this
     * stop, this selector declares this value for this property".
     */
    private static final List<Expected> MATRIX = List.of(
        // ── ≥ 1180: exactly as drawn ──
        new Expected(AS_DRAWN, ".sidebar", "width", "216px"),
        new Expected(AS_DRAWN, ".content__body", "padding", "22px 26px"),
        new Expected(AS_DRAWN, ".headerbar", "height", "60px"),
        new Expected(AS_DRAWN, ".kpi-row", "grid-template-columns", "repeat(4, 1fr)"),
        new Expected(AS_DRAWN, ".tabs", "display", "flex"),
        new Expected(AS_DRAWN, ".tabs__select", "display", "none"),

        // ── 1024–1179: the 56px icon rail; rails stay beside main, narrowed to 300 ──
        new Expected(1179, ".sidebar", "width", "56px"),
        new Expected(1179, ".content__body", "padding", "18px 20px"),
        new Expected(1179, ".headerbar", "height", "56px"),
        new Expected(1179, ".grid--main-330", "grid-template-columns", "1fr 300px"),
        new Expected(1179, ".grid--320-main", "grid-template-columns", "300px 1fr"),

        // ── 768–1023: the floor ──
        new Expected(1023, ".content__body", "padding", "16px"),
        new Expected(1023, ".card__head", "padding", "14px 14px 11px"),
        new Expected(1023, ".card__body", "padding", "0 14px 14px"),
        new Expected(1023, ".kpi", "padding", "14px"),
        new Expected(1023, ".kpi-row", "grid-template-columns", "1fr 1fr"),
        new Expected(1023, ".tabs", "display", "none"),
        new Expected(1023, ".tabs__select", "display", "block"),
        new Expected(1023, ".grid--main-300", "grid-template-columns", "1fr"),

        /*
          The two-up rail, and the reason it is not `1fr` beside the row above it. The spec's
          Property detail frame: "Owner balances and Timeline sit two-up under the units table
          instead of in a 330px rail" — so main spans the pair and the rail's cards share the row
          beneath. Listed here because it is the one cell of the matrix where two grids that look
          alike at every other width deliberately part company, which is exactly the kind of
          difference a later tidy-up merges back together.
        */
        new Expected(1023, ".grid--main-330", "grid-template-columns", "1fr 1fr"));

    @Test
    void theStylesheetMatchesTheBreakpointMatrix() throws IOException {
        assertThat(driftFrom(MATRIX, CSS))
            .as("the stylesheet no longer matches the responsive handoff's breakpoint table — "
                + "if the handoff changed, change the matrix in this test and say so; do not "
                + "relax the assertion to whatever the stylesheet now says")
            .isEmpty();
    }

    /**
     * The scanner is known to still bite.
     *
     * <p>{@code bad-breakpoint-drift.css} carries the whole matrix with exactly one value moved —
     * the 768 gutter set to 18px, which is the 1024 value and therefore the most plausible way for
     * this to actually go wrong: someone tidies two stops into one. Asserting the violation names
     * that property rather than merely that the list is non-empty, because a scanner that reports
     * everything is as useless as one that reports nothing, and a fixture full of absent rules would
     * prove only that absence is detected (refactoring.md rule 20).
     */
    @Test
    void theScannerCatchesADriftedValue() throws IOException {
        List<String> drift = driftFrom(MATRIX, FIXTURES);

        assertThat(drift)
            .as("bad-breakpoint-drift.css moves the 768 page gutter to the 1024 value; this "
                + "scanner must say so, and must say which cell moved")
            .anyMatch(reported -> reported.contains(".content__body")
                && reported.contains("padding")
                && reported.contains("@1023"));
    }

    // ----------------------------------------------------------------------------------------------
    // Scanning
    // ----------------------------------------------------------------------------------------------

    /** Every row of {@code matrix} whose value is absent from, or different in, the CSS under {@code root}. */
    private static List<String> driftFrom(List<Expected> matrix, Path root) throws IOException {
        List<Rule> rules = rulesIn(root);
        List<String> drift = new ArrayList<>();
        for (Expected row : matrix) {
            Optional<String> actual = declaredValue(rules, row);
            if (actual.isEmpty()) {
                drift.add(row + " — not declared at all");
            } else if (!actual.get().equals(row.value())) {
                drift.add(row + " — but the stylesheet says '" + actual.get() + "'");
            }
        }
        return drift;
    }

    /**
     * The value {@code row}'s selector declares for its property in its media context.
     *
     * <p>The LAST such declaration wins, which is how the cascade resolves two rules of equal
     * specificity in one layer — so a value overridden later in the same file is reported as the
     * later one, the same thing the browser would use.
     */
    private static Optional<String> declaredValue(List<Rule> rules, Expected row) {
        Optional<String> found = Optional.empty();
        for (Rule rule : rules) {
            if (rule.mediaMaxWidth() != row.mediaMaxWidth() || !rule.selects(row.selector())) {
                continue;
            }
            Optional<String> value = rule.value(row.property());
            if (value.isPresent()) {
                found = value;
            }
        }
        return found;
    }

    /**
     * Every style rule under {@code root}, tagged with the {@code max-width} of the {@code @media}
     * enclosing it, or {@link #AS_DRAWN} when there is none.
     *
     * <p>{@code @layer} blocks are walked through rather than recorded: a layer decides who wins a
     * conflict, and this scan is asking what a selector declares, not who wins. Nested media is
     * carried on a stack so leaving an inner query restores the outer one instead of falling back to
     * "no query", which would quietly file a 768 rule under ≥1180.
     */
    private static List<Rule> rulesIn(Path root) throws IOException {
        List<Rule> rules = new ArrayList<>();
        for (Path file : cssFiles(root)) {
            String css = stripComments(Files.readString(file));
            Deque<Integer> enclosing = new ArrayDeque<>();
            Deque<Boolean> isMedia = new ArrayDeque<>();
            int media = AS_DRAWN;
            StringBuilder prelude = new StringBuilder();

            for (int i = 0; i < css.length(); i++) {
                char c = css.charAt(i);
                if (c == '{') {
                    String head = prelude.toString().trim();
                    prelude.setLength(0);
                    if (head.startsWith("@media")) {
                        enclosing.push(media);
                        isMedia.push(true);
                        media = maxWidthOf(head).orElse(media);
                    } else if (head.startsWith("@")) {
                        isMedia.push(false);
                    } else {
                        // A style rule. This stylesheet uses no CSS nesting, so its body ends at the
                        // next brace of either kind and cannot contain one.
                        int close = css.indexOf('}', i);
                        if (close < 0) {
                            break;
                        }
                        rules.add(new Rule(media, head, css.substring(i + 1, close)));
                        i = close;
                    }
                } else if (c == '}') {
                    if (!isMedia.isEmpty() && isMedia.pop()) {
                        media = enclosing.pop();
                    }
                    prelude.setLength(0);
                } else {
                    prelude.append(c);
                }
            }
        }
        return rules;
    }

    private static final Pattern MAX_WIDTH = Pattern.compile("max-width:\\s*(\\d+)px");

    private static Optional<Integer> maxWidthOf(String mediaPrelude) {
        Matcher m = MAX_WIDTH.matcher(mediaPrelude);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /** Comments removed so a brace or a declaration inside one cannot be read as code. */
    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /**
     * Every {@code .css} under {@code root}, walked rather than listed so a new stylesheet is covered
     * the moment it is added. Fails loudly on an empty result: a root that resolved to nothing looks
     * exactly like a clean tree.
     */
    private static List<Path> cssFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                "no stylesheets at " + root.toAbsolutePath() + " — this guard would pass unchecked");
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> css = files.filter(p -> p.toString().endsWith(".css")).sorted().toList();
            if (css.isEmpty()) {
                throw new IllegalStateException(
                    "no .css under " + root.toAbsolutePath() + " — this guard would pass unchecked");
            }
            return css;
        }
    }

    // ----------------------------------------------------------------------------------------------

    /** One cell of the spec's table. */
    private record Expected(int mediaMaxWidth, String selector, String property, String value) {

        @Override
        public String toString() {
            String stop = mediaMaxWidth == AS_DRAWN ? "as drawn (≥1180)" : "@" + mediaMaxWidth;
            return stop + " " + selector + " { " + property + ": " + value + " }";
        }
    }

    /** One style rule: its media context, its selector list, and its declaration block. */
    private record Rule(int mediaMaxWidth, String selectors, String body) {

        /** Whether this rule's selector list names {@code selector} exactly. */
        boolean selects(String selector) {
            for (String each : selectors.split(",")) {
                if (each.trim().equals(selector)) {
                    return true;
                }
            }
            return false;
        }

        /** The value this rule declares for {@code property}, with internal whitespace collapsed. */
        Optional<String> value(String property) {
            for (String declaration : body.split(";")) {
                String[] parts = declaration.split(":", 2);
                if (parts.length == 2 && parts[0].trim().equals(property)) {
                    return Optional.of(parts[1].trim().replaceAll("\\s+", " "));
                }
            }
            return Optional.empty();
        }
    }
}
