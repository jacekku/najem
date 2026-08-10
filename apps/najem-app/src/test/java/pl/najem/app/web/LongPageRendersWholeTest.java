package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import pl.najem.app.SharedDatabase;
import pl.najem.pm.application.PortfolioService;
import pl.najem.pm.domain.Owner;
import pl.najem.reporting.application.ProjectionRunner;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A long screen renders all the way to {@code </html>}, in front of a REAL servlet container.
 *
 * <p><b>This class exists because every other screen test in this application is structurally
 * incapable of catching the defect it covers.</b> They all use MockMvc, whose
 * {@code MockHttpServletResponse} has no output buffer: it never commits mid-render, so anything
 * that depends on the response not yet being committed always succeeds there. Tomcat commits as
 * soon as its 8 kB buffer fills, which on a long page happens partway through the body.
 *
 * <p>The bug that earned it: Spring Security defers CSRF token resolution to first use, and the
 * session that stores the token was therefore created at the first {@code th:action} Thymeleaf
 * rendered. On the unit screen's Najmy tab the tenancy table pushed that form past 8 kB, so the
 * render died with {@code Cannot create a session after the response has been committed} — the
 * browser got HTTP 200 and half a page, and the error handler could not replace it because the
 * status line had already gone out. Ten MockMvc assertions on that exact tab were green throughout.
 * The fix is {@code SecurityConfig.eagerCsrfToken()}; this is what says it stayed fixed.
 *
 * <p><b>Asserting the closing tag, not the status.</b> The status was 200 while the page was
 * truncated — that is the whole shape of this failure — so a status check proves nothing here.
 *
 * <p>{@code webEnvironment = RANDOM_PORT} is the point and the cost: it is the only setting that
 * puts a real Tomcat and a real buffer under the test. One context, one screen, deliberately not
 * repeated for every page — the mechanism is application-wide, so covering the longest page covers
 * the mechanism.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "najem.bootstrap.operator-subject=" + LongPageRendersWholeTest.OPERATOR,
        "najem.security.permit-all=true",
        "najem.bank.fake.enabled=true",
        "najem.bank.base-url=http://localhost:8081"})
@Tag("integration")
class LongPageRendersWholeTest extends SharedDatabase {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000090";

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired PortfolioService portfolio;
    @Autowired ProjectionRunner projections;

    static UUID agency;
    static UUID unitId;

    @BeforeEach
    void aUnit() {
        UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
        if (agency != null) {
            return;
        }
        agency = workspaces.create("Agencja Długich Stron", operator, LocalDate.now());
        var property = portfolio.createProperty(agency, "ul. Długa 1, 00-238 Warszawa",
            List.of(new Owner(UUID.randomUUID(), new BigDecimal("100")))).propertyId();
        unitId = portfolio.addUnit(agency, property, "m. 1", new BigDecimal("2500"));
        projections.runOnce();
    }

    /**
     * The Najmy tab — the longest screen in the application, and the one the defect surfaced on.
     *
     * <p>It carries the tenancy table, the parties card, the interest table and the lead form, and
     * that last one is the first {@code th:action} on the page. Ordering matters to this test: the
     * form has to come far enough down the body for the buffer to have committed before it.
     */
    @Test
    void theLongestTabReachesItsClosingTag() {
        var response = http.getForEntity(url("/units/" + unitId + "?tab=najmy"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .as("a truncated page answers 200 with half a body — the closing tag is what says the "
                + "render finished rather than died after the response committed")
            .isNotNull()
            .contains("Dodaj zainteresowanego");
        // Stripped before comparing: the template file ends with a newline and it is served, so a
        // bare endsWith("</html>") fails on a page that is perfectly complete.
        assertThat(response.getBody().strip()).endsWith("</html>");
    }

    /**
     * And the form on it carries a usable CSRF token.
     *
     * <p>Separate from the assertion above and not folded into it: making the page merely FINISH is
     * one bug fixed, and a page that finishes because the token silently stopped being rendered
     * would pass that assertion while leaving every form on the screen unsubmittable. Both have to
     * hold.
     */
    @Test
    void andTheFormOnItStillCarriesACsrfToken() {
        var body = http.getForObject(url("/units/" + unitId + "?tab=najmy"), String.class);

        assertThat(body).contains("name=\"_csrf\"");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
