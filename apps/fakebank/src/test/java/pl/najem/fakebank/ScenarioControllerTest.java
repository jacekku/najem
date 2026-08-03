package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ScenarioController.class, AccountsController.class})
@Import({TransactionStore.class, ScenarioCatalog.class})
class ScenarioControllerTest {

    /**
     * The store is a singleton across the test methods sharing this context, so each test seeds
     * into its own IBAN rather than relying on cleanup between methods.
     */
    private static String seedBody(String iban) {
        return """
            {"name":"partial-then-topup","iban":"%s","reference":"NAJEM/M1/2026",
             "amount":2500.00,"anchorDate":"2026-09-01"}
            """.formatted(iban);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void seedsAScenarioAndReportsWhatItSeeded() throws Exception {
        mvc.perform(post("/api/scenarios").contentType(APPLICATION_JSON).content(seedBody("PL01")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.seeded").value(2))
            .andExpect(jsonPath("$.externalIds[0]").value("partial-then-topup/NAJEM-M1-2026/0"))
            .andExpect(jsonPath("$.externalIds[1]").value("partial-then-topup/NAJEM-M1-2026/1"));
    }

    @Test
    void seededLinesAreVisibleThroughTheExistingTransactionsEndpoint() throws Exception {
        mvc.perform(post("/api/scenarios").contentType(APPLICATION_JSON).content(seedBody("PL02")))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL02/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].title").value("NAJEM/M1/2026"))
            .andExpect(jsonPath("$[0].creditDebitIndicator").value("CRDT"));
    }

    @Test
    void rejectsAnUnknownScenarioName() throws Exception {
        mvc.perform(post("/api/scenarios").contentType(APPLICATION_JSON)
                .content("""
                    {"name":"nonsense","iban":"PL61","reference":"NAJEM/M1/2026",
                     "amount":2500.00,"anchorDate":"2026-09-01"}
                    """))
            .andExpect(status().isBadRequest());
    }
}
