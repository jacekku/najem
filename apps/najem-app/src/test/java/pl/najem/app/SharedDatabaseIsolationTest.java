package pl.najem.app;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every application test class must declare its own {@code OPERATOR} subject.
 *
 * <p><b>Why this is a test and not a convention.</b> The application tests share one Postgres
 * ({@link SharedDatabase}), and what keeps them from seeing each other is not a private database —
 * it is that memberships resolve per subject, so each class's operator holds only the agency that
 * class created. Two classes sharing a subject silently pool their agencies, and
 * {@code WebWorkspaceResolver} then either serves a neighbour's data or raises
 * {@code ChoiceRequiredException} on a screen that expected one membership.
 *
 * <p><b>And why the ordering makes it worth a tripwire.</b> A duplicate does not reliably fail. It
 * fails only when the class that creates the agency happens to run before the class that assumes
 * none — discovery order, which nobody controls and which changes when a file is renamed. Both
 * mutations were run: pointing {@code NoAgencyScreenTest} at {@code WebWorkspaceTest}'s subject
 * left the suite GREEN, because that class runs later; pointing it at
 * {@code AddPropertyScreenTest}'s subject, which runs earlier, turned it red. A defect that hides
 * behind file order is precisely the kind that must be caught structurally rather than by whoever
 * remembers (rule 9).
 *
 * <p>Fast tier on purpose: it reads source files and boots nothing, so the check costs nothing and
 * runs on every inner-loop build rather than only at a merge.
 */
class SharedDatabaseIsolationTest {

    private static final Pattern OPERATOR =
        Pattern.compile("static final String OPERATOR = \"([^\"]+)\";");

    @Test
    void noTwoApplicationTestsShareAnOperatorSubject() throws IOException {
        var bySubject = declaredOperators().stream()
            .collect(Collectors.groupingBy(Declaration::subject,
                Collectors.mapping(Declaration::owner, Collectors.toList())));

        var shared = bySubject.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(shared)
            .describedAs("these classes share an operator subject, so they share its agencies — "
                + "give each its own, do not delete this test")
            .isEmpty();
    }

    /**
     * Guards the guard: a regex that matches nothing is indistinguishable from a regex that passes
     * (rule 20). If the constant is ever renamed, this fails rather than quietly checking nothing.
     */
    @Test
    void theScanFindsTheOperatorsItIsMeantToCompare() throws IOException {
        assertThat(declaredOperators())
            .describedAs("no OPERATOR constants found — the scan has stopped matching and this "
                + "whole class has become decoration")
            .hasSizeGreaterThan(10);
    }

    private record Declaration(String owner, String subject) {}

    private static List<Declaration> declaredOperators() throws IOException {
        var root = Path.of("src/test/java/pl/najem/app");
        var found = new ArrayList<Declaration>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Test.java")).toList()) {
                var matcher = OPERATOR.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(new Declaration(file.getFileName().toString(), matcher.group(1)));
                }
            }
        }
        return found;
    }
}
