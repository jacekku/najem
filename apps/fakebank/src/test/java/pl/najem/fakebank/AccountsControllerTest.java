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
}
