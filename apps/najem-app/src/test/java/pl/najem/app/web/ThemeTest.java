package pl.najem.app.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Choosing light or dark, and the two ways that choice could go wrong.
 *
 * <p>The theme itself is cosmetic. Two things about it are not: the control feeds a person-supplied
 * path into a redirect, which is the shape of an open redirect; and "no choice" has to stay
 * distinguishable from "chose light", or somebody who never touched the control stops tracking
 * their operating system.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + ThemeTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
class ThemeTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000009";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;
    @Autowired
    pl.najem.um.application.UserService users;
    @Autowired
    pl.najem.um.application.WorkspaceService workspaces;

    /**
     * These assertions read the rendered shell, so they need a screen that renders it. That is
     * "/", not "/login": the sign-in page is {@code @Conditional(IssuerConfigured)} and does not
     * exist in a permit-all context, where there is nothing to sign in to. And "/" is the agency
     * screen, so it needs an agency to be the screen of.
     */
    static boolean seeded;

    @org.junit.jupiter.api.BeforeEach
    void anAgency() {
        if (seeded) {
            return;
        }
        java.util.UUID operator = users.findBySubject(java.util.UUID.fromString(OPERATOR))
            .orElseGet(() -> users.register(java.util.UUID.fromString(OPERATOR),
                java.time.LocalDate.now()));
        workspaces.create("Agencja Motywu", operator, java.time.LocalDate.now());
        seeded = true;
    }

    /**
     * No cookie must render no attribute at all — not {@code data-theme="light"}.
     *
     * <p>This is the test that keeps the OS default working. A stamped "light" also looks correct
     * on a light machine, which is exactly why the mistake would survive review: the difference
     * only shows up on somebody else's dark-set laptop, months later.
     */
    @Test
    void withoutACookieTheDocumentSaysNothingAboutTheme() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("data-theme"))));
    }

    @Test
    void anExplicitChoiceIsStampedOnTheDocument() throws Exception {
        mvc.perform(get("/").cookie(new Cookie(ThemeAdvice.COOKIE, "dark")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-theme=\"dark\"")));
    }

    /**
     * A hand-edited cookie is untrusted input on its way into an attribute on {@code <html>}.
     * Anything unrecognised falls back to following the operating system rather than being echoed.
     */
    @Test
    void anUnrecognisedCookieIsTreatedAsNoChoice() throws Exception {
        mvc.perform(get("/")
                .cookie(new Cookie(ThemeAdvice.COOKIE, "\"><script>alert(1)</script>")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("data-theme"))));
    }

    @Test
    void choosingReturnsToTheScreenYouWereReading() throws Exception {
        mvc.perform(post("/theme").with(csrf())
                .param("theme", "dark")
                .param("return", "/properties"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/properties"));
    }

    /**
     * The open-redirect cases. Each of these is a string that looks like a path and is not one —
     * {@code //host} is protocol-relative, and {@code /\host} is the same trick spelled with a
     * backslash that some browsers normalise. A theme control that can bounce somebody to another
     * origin is a phishing primitive on the sign-in page, which is where this control also lives.
     */
    @Test
    void aReturnPathPointingOffSiteIsRefused() throws Exception {
        for (String hostile : new String[]{
            "//evil.example",
            "/\\evil.example",
            "https://evil.example/x",
            "http://evil.example",
            "evil.example"}) {

            mvc.perform(post("/theme").with(csrf())
                    .param("theme", "dark")
                    .param("return", hostile))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        }
    }

    /** A value that is neither light nor dark clears the cookie rather than inventing a theme. */
    @Test
    void anUnknownThemeClearsTheChoice() throws Exception {
        var response = mvc.perform(post("/theme").with(csrf())
                .param("theme", "solarized")
                .param("return", "/"))
            .andExpect(status().is3xxRedirection())
            .andReturn().getResponse();

        Cookie written = response.getCookie(ThemeAdvice.COOKIE);
        assertThat(written).isNotNull();
        assertThat(written.getMaxAge()).isZero();
    }

    @Test
    void theChoiceOutlivesTheSession() throws Exception {
        var response = mvc.perform(post("/theme").with(csrf())
                .param("theme", "dark")
                .param("return", "/"))
            .andReturn().getResponse();

        Cookie written = response.getCookie(ThemeAdvice.COOKIE);
        assertThat(written).isNotNull();
        assertThat(written.getValue()).isEqualTo("dark");
        // Signing out must not reset how somebody reads the screen.
        assertThat(written.getMaxAge()).isGreaterThan(60 * 60 * 24 * 30);
    }
}
