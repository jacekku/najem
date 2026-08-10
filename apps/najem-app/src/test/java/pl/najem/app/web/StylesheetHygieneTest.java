package pl.najem.app.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two source scans over the stylesheets, run without Spring and without a database.
 *
 * <p>A sibling of {@link TemplateHygieneTest} rather than more tripwires inside it, because the
 * subject is different: that class scans {@code src/main/resources/templates} for markup defects,
 * this one scans {@code src/main/resources/static/css} for defects in the cascade itself. Both
 * defects below shipped on the design-system branch, and neither is visible in a rendered page or
 * in review — one silently overrides a component, the other silently removes the only thing a
 * keyboard user has to go on.
 *
 * <p>Same two-root discipline as its sibling: {@link #CSS} is the real stylesheet the application
 * serves, {@link #FIXTURES} holds committed broken specimens whose only job is to prove each
 * tripwire still bites. Every tripwire asserts both halves in one {@code @Test} — the real tree is
 * clean AND the fixture is still caught — because a scanner that returns nothing is
 * indistinguishable from a scanner that is broken (refactoring.md rule 20).
 */
class StylesheetHygieneTest {

    private static final Path CSS = Path.of("src/main/resources/static/css");
    private static final Path FIXTURES = Path.of("src/test/resources/hygiene-fixtures/css");

    /**
     * No {@code @media} block sits outside a {@code @layer} block.
     *
     * <p>This stylesheet declares its order once — {@code @layer tokens, base, layout, components,
     * screens;} in {@code base.css} — and every rule lives inside one of those layers. That is what
     * lets a screen-specific rule override a component without a specificity war, and it is
     * load-bearing rather than tidy.
     *
     * <p>An unlayered rule beats every layered rule regardless of selector specificity. Cascade
     * layers are consulted before specificity, and declarations outside all layers sort ABOVE every
     * layer. So a responsive tweak written as a bare {@code @media} at the end of a file does not
     * merely participate — it wins over {@code @layer components} unconditionally, at every width the
     * query matches, and looks like a specificity puzzle to whoever debugs it.
     *
     * <p>Two layer-ordering bugs shipped on the branch that introduced these layers. The second was
     * a declaration in {@code @layer components} that {@code @layer screens} silently outranked, and
     * the fix carried a note saying a mechanical check would be worth more than another comment.
     * This is that check.
     *
     * <p><b>When this goes red, move the {@code @media} inside the layer the rules belong to. Do not
     * add an exception.</b> A rule that genuinely must outrank every layer is a rule that has not
     * been assigned to one yet, and naming its layer is the fix.
     */
    @Test
    void noMediaQuerySitsOutsideALayer() throws IOException {
        List<String> real = unlayeredMediaQueries(CSS);
        assertThat(real)
            .as("a @media outside every @layer outranks @layer components at any specificity — "
                + "move it inside the layer its rules belong to, do not except it here")
            .isEmpty();

        List<String> fixture = unlayeredMediaQueries(FIXTURES);
        assertThat(fixture)
            .as("bad-unlayered-media.css exists so this scanner is known to still bite; a clean "
                + "real tree proves nothing on its own (refactoring.md rule 20)")
            .isNotEmpty();
    }

    /**
     * {@code :focus-visible} declares a visible {@code outline}.
     *
     * <p>The one defect on this branch that a person could not have seen by looking at the app, and
     * that no contrast tool can catch. {@code tools/contrast} reads {@code tokens.css} and confirms
     * {@code --accent-green} clears 3:1 against both backgrounds — which stays true whether or not
     * anything actually draws an outline in it. The tool checks the colour; only this checks that the
     * colour is used.
     *
     * <p>What happened: the rule was rewritten as {@code outline: none} plus {@code border-color} and
     * a 10% alpha {@code box-shadow}. A text input still showed focus, because it has a border to
     * recolour, so the change looked fine on the screen most likely to be tested. Every link, button,
     * chip, nav item and tab showed nothing at all — a border-colour focus style is a focus style
     * that exists on inputs and nowhere else — and the halo composites to 1.14:1 against white where
     * WCAG 1.4.11 asks for 3:1. The stylesheet it replaced carried a comment warning against exactly
     * this: reconciling a bank statement is a two-hundred-tab job, and an invisible focus ring makes
     * "which row am I about to confirm" unanswerable.
     *
     * <p><b>When this goes red, restore the outline. Do not delete this test, and do not satisfy it
     * with {@code outline-width: 0}.</b> The assertion is deliberately about {@code outline} and not
     * about {@code box-shadow} or {@code border-color}: {@code outline} is the one property that
     * renders on every focusable element regardless of whether that element has a border or a fill,
     * which is why it is the only one that can carry this job.
     */
    @Test
    void focusVisibleDeclaresAVisibleOutline() throws IOException {
        assertThat(declaresVisibleFocusOutline(CSS))
            .as(":focus-visible must declare a visible outline — a focus style built from "
                + "border-color and a box-shadow exists on inputs and nowhere else")
            .isTrue();

        assertThat(declaresVisibleFocusOutline(FIXTURES))
            .as("bad-focus-outline-none.css exists so this scanner is known to still bite; it "
                + "declares :focus-visible with outline:none and must be reported as not visible")
            .isFalse();
    }

    // ----------------------------------------------------------------------------------------------
    // Scanning
    // ----------------------------------------------------------------------------------------------

    /**
     * Every {@code @media} in {@code root} that is not nested inside a {@code @layer} block, as
     * {@code file:line} strings.
     *
     * <p>Brace depth is tracked from the start of each file and a comment-stripped copy is scanned,
     * because a braces-in-a-comment miscount would shift every subsequent depth reading and the scan
     * would silently stop meaning anything.
     */
    private static List<String> unlayeredMediaQueries(Path root) throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : cssFiles(root)) {
            String content = stripComments(Files.readString(file));
            int depth = 0;
            for (int i = 0; i < content.length(); i++) {
                if (depth == 0 && content.startsWith("@media", i)) {
                    offenders.add(file.getFileName() + ":" + lineOf(content, i));
                }
                char c = content.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
        }
        return offenders;
    }

    /**
     * Whether some {@code :focus-visible} rule in {@code root} declares an {@code outline} that draws
     * something.
     *
     * <p>Reads the declaration block after the selector and requires an {@code outline} shorthand or
     * {@code outline-style} whose value is neither {@code none} nor {@code 0}. {@code outline: none}
     * is the exact form that shipped, so it must not count, and a zero width must not either.
     */
    private static boolean declaresVisibleFocusOutline(Path root) throws IOException {
        for (Path file : cssFiles(root)) {
            String content = stripComments(Files.readString(file));
            int from = 0;
            int at;
            while ((at = content.indexOf(":focus-visible", from)) >= 0) {
                from = at + 1;
                int open = content.indexOf('{', at);
                int close = open < 0 ? -1 : content.indexOf('}', open);
                if (open < 0 || close < 0) {
                    continue;
                }
                String block = content.substring(open + 1, close);
                for (String declaration : block.split(";")) {
                    String[] parts = declaration.split(":", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    String property = parts[0].trim();
                    String value = parts[1].trim().toLowerCase();
                    boolean isOutline = property.equals("outline") || property.equals("outline-style");
                    if (isOutline && !value.equals("none") && !value.equals("0") && !value.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Comments removed so a brace or a declaration inside one cannot be read as code. */
    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static int lineOf(String content, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Every {@code .css} under {@code root}, walked rather than listed by hand so a new stylesheet is
     * covered the moment it is added. Fails loudly on an empty result: a root that resolved to
     * nothing would otherwise look exactly like a clean tree.
     */
    private static List<Path> cssFiles(Path root) throws IOException {
        assertThat(Files.isDirectory(root))
            .as("%s must exist — a missing root would make every scan below pass on nothing", root)
            .isTrue();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.getFileName().toString().endsWith(".css")).sorted().toList();
        }
        assertThat(files).as("no stylesheets found under %s", root).isNotEmpty();
        return files;
    }
}
