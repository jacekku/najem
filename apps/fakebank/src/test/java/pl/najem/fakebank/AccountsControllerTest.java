package pl.najem.fakebank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountsController.class)
class AccountsControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void seedsAndListsTransactionsWithSinceFilter() throws Exception {
        mvc.perform(post("/api/accounts/PL61/transactions").contentType(APPLICATION_JSON)
                .content("""
                    {"id":"tx-1","amount":2500,"title":"NAJEM/M1/2026","bookingDate":"2026-09-03"}
                    """))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL61/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("tx-1"))
            .andExpect(jsonPath("$[0].title").value("NAJEM/M1/2026"));

        mvc.perform(get("/api/accounts/PL61/transactions").param("since", "2026-09-04"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void roundTripsTheWidenedFieldsAndToleratesTheirAbsence() throws Exception {
        mvc.perform(post("/api/accounts/PL62/transactions").contentType(APPLICATION_JSON)
                .content("""
                    {"id":"tx-wide","amount":2500,"title":"NAJEM/M1/2026","bookingDate":"2026-09-03",
                     "counterpartyName":"JAN KOWALSKI","counterpartyIban":"PL27114020040000300201355387",
                     "bankReference":"BNP00012345","valueDate":"2026-09-04",
                     "creditDebitIndicator":"CRDT","currency":"PLN"}
                    """))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL62/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].counterpartyName").value("JAN KOWALSKI"))
            .andExpect(jsonPath("$[0].counterpartyIban").value("PL27114020040000300201355387"))
            .andExpect(jsonPath("$[0].bankReference").value("BNP00012345"))
            .andExpect(jsonPath("$[0].valueDate").value("2026-09-04"))
            .andExpect(jsonPath("$[0].creditDebitIndicator").value("CRDT"))
            .andExpect(jsonPath("$[0].currency").value("PLN"));

        // The Phase 0 payload shape must still be accepted, with the new fields absent.
        mvc.perform(post("/api/accounts/PL63/transactions").contentType(APPLICATION_JSON)
                .content("""
                    {"id":"tx-narrow","amount":2500,"title":"NAJEM/M1/2026","bookingDate":"2026-09-03"}
                    """))
            .andExpect(status().isCreated());

        mvc.perform(get("/api/accounts/PL63/transactions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("tx-narrow"))
            .andExpect(jsonPath("$[0].counterpartyIban").doesNotExist());
    }
}
