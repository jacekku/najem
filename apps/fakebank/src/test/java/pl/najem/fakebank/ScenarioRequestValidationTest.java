package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A malformed seeding request is the caller's mistake and must be answered as one.
 *
 * <p>Found by making it: seeding with {@code {"scenario": …, "dueDate": …}} — the field names a
 * reasonable person guesses, rather than {@code name} and {@code anchorDate} — produced a
 * <strong>500</strong>. Jackson bound the unknown names to nothing, left two components null, and
 * the null surfaced deep inside the catalogue as a stack trace.
 *
 * <p>A 500 tells the caller the server is broken when the request was. During a demonstration that
 * is thirty seconds of someone believing they have found a bug in the system rather than a typo in
 * their JSON, and there is no message to tell them otherwise.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioRequestValidationTest {

    private static final String IBAN = "PL61109010140000071219812874";

    @Autowired
    MockMvc mvc;

    /** The exact body that produced the 500. */
    @Test
    void plausibleButWrongFieldNamesAreRejectedAsABadRequest() throws Exception {
        String body = """
            {"scenario":"on-time","iban":"%s","amount":"2500.00",
             "reference":"NAJEM/M1/2026","dueDate":"2026-09-10"}""".formatted(IBAN);

        String message = mvc.perform(post("/api/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();

        assertThat(message)
            .as("the response must name what is missing, or the caller is left guessing")
            .contains("name");
    }

    @Test
    void aRequestMissingItsAmountIsRejected() throws Exception {
        String body = """
            {"name":"on-time","iban":"%s","reference":"NAJEM/M1/2026",
             "anchorDate":"2026-09-10"}""".formatted(IBAN);

        mvc.perform(post("/api/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    /**
     * {@code lump-sum-two-tenancies} needs a second, independent reference and refuses to invent
     * one. That refusal was reaching the caller as a 500 for the same reason.
     */
    @Test
    void aScenarioNeedingASecondReferenceSaysSoRatherThanFailing() throws Exception {
        String body = """
            {"name":"lump-sum-two-tenancies","iban":"%s","reference":"NAJEM/M1/2026",
             "amount":"2500.00","anchorDate":"2026-09-10"}""".formatted(IBAN);

        mvc.perform(post("/api/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    /** An unknown scenario name was already a 400; it must stay one. */
    @Test
    void anUnknownScenarioIsStillABadRequest() throws Exception {
        String body = """
            {"name":"no-such-scenario","iban":"%s","reference":"NAJEM/M1/2026",
             "amount":"2500.00","anchorDate":"2026-09-10"}""".formatted(IBAN);

        mvc.perform(post("/api/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());
    }

    /** The valid request must still work — a validation that rejects everything passes the rest. */
    @Test
    void aWellFormedRequestStillSeeds() throws Exception {
        String body = """
            {"name":"on-time","iban":"%s","reference":"NAJEM/OK/2026",
             "amount":"2500.00","anchorDate":"2026-09-10"}""".formatted(IBAN);

        mvc.perform(post("/api/scenarios")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
    }
}
