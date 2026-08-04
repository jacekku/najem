package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import pl.najem.reporting.application.SearchQuery;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grouping that replaced a Thymeleaf selection expression, tested without a database.
 *
 * <p><b>Why this exists is worth more than what it asserts.</b> The first version grouped hits in
 * the template with {@code hits.?[kind == 'property']}. That cannot resolve {@code kind} on a
 * record, so it threw at render time — and the whole application suite stayed green, because the
 * expression sat behind {@code th:if="${!hits.isEmpty()}"} and <em>no test has ever produced a
 * search hit</em>. The screen answered 200 with no query and 500 with any query that matched
 * something.
 *
 * <p>Found by opening the page, not by a test. The fix moves the logic somewhere the compiler can
 * see it and a test can reach it with no fixtures at all.
 *
 * <p><b>Still uncovered, stated so nobody reads this as coverage:</b> nothing here renders the
 * template with a non-empty list. That needs seeded Reporting projections, and the honest status
 * is that the end-to-end hit path is verified by having looked at it, not by this file.
 */
class SearchGroupingTest {

    private static final SearchQuery.Hit PROPERTY =
        new SearchQuery.Hit("property", UUID.randomUUID(), "ul. Marszałkowska 12", UUID.randomUUID());
    private static final SearchQuery.Hit UNIT =
        new SearchQuery.Hit("unit", UUID.randomUUID(), "m. 1", UUID.randomUUID());

    @Test
    void splitsHitsByKindAndKeepsNothingThatDoesNotBelong() {
        var hits = List.of(PROPERTY, UNIT);

        assertThat(SearchScreenController.ofKind(hits, "property")).containsExactly(PROPERTY);
        assertThat(SearchScreenController.ofKind(hits, "unit")).containsExactly(UNIT);
    }

    /** A kind Reporting has not mentioned appears in neither group rather than in both. */
    @Test
    void anUnknownKindIsDroppedRatherThanGuessed() {
        var contact = new SearchQuery.Hit("contact", UUID.randomUUID(), "Jan Kowalski", null);

        assertThat(SearchScreenController.ofKind(List.of(contact), "property")).isEmpty();
        assertThat(SearchScreenController.ofKind(List.of(contact), "unit")).isEmpty();
    }
}
