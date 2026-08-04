package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scaffold every later screen renders into. Boots the whole application context on purpose:
 * a module whose beans stop being constructible fails here as well as in e2e, which is the second
 * reason the UI lives in the composition root (najem-build seq 130).
 */
@SpringBootTest(properties = "najem.security.permit-all=true")
@AutoConfigureMockMvc
@Testcontainers
class WebScaffoldTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mvc;

    @Test
    void servesAnHtmlPageAtTheRoot() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void servesHtmxFromTheApplicationRatherThanACdn() throws Exception {
        mvc.perform(get("/vendor/htmx.min.js"))
            .andExpect(status().isOk());
    }

    /**
     * A page that pulls its script from a third party is a page whose render depends on that party
     * being reachable, in an application handling tenancy-scoped financial data (plan decision E).
     */
    @Test
    void theLayoutReferencesNoExternalHost() throws Exception {
        String html = mvc.perform(get("/"))
            .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(html)
            .doesNotContain("http://")
            .doesNotContain("https://");
    }
}
