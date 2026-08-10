package pl.najem.app.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.application.ArrearsBoardProjection;
import pl.najem.acc.application.InvoiceRepository;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.pm.application.TenancyBoardProjection;
import pl.najem.pm.application.TenancyBoardRow;
import pl.najem.pm.domain.Tenancy;
import pl.najem.um.application.UserService;
import pl.najem.um.application.WorkspaceService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Najmy register: its six columns, and the two cells that carry a claim rather than a value —
 * Saldo's sign and Status's pill.
 *
 * <p>The three read ports are mocked, the shape {@code ReportsScreenTest} already uses. What this
 * asks is "given rows from PM, balances from accounting and names from contacts, does the screen
 * compose them correctly" — not whether any module computes its own answer right, which is each
 * module's own suite's job. Composition is what this controller does and therefore all it can get
 * wrong.
 */
@SpringBootTest(properties = {
    "najem.bootstrap.operator-subject=" + TenanciesScreenTest.OPERATOR,
    "najem.security.permit-all=true",
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874"})
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class TenanciesScreenTest {

    static final String OPERATOR = "3f1d9c22-0000-4000-8000-000000000070";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @MockBean TenancyBoardProjection register;
    @MockBean InvoiceRepository invoices;
    @MockBean ArrearsBoardProjection board;
    @MockBean ContactDirectory contacts;

    static boolean seeded;

    private static final UUID ANNA = UUID.randomUUID();

    @BeforeEach
    void anAgency() {
        if (!seeded) {
            UUID operator = users.findBySubject(UUID.fromString(OPERATOR))
                .orElseGet(() -> users.register(UUID.fromString(OPERATOR), LocalDate.now()));
            workspaces.create("Agencja Najmów", operator, LocalDate.now());
            seeded = true;
        }
        when(register.forWorkspace(any())).thenReturn(List.of());
        when(invoices.outstandingByTenancy(any())).thenReturn(Map.of());
        when(board.forWorkspace(any())).thenReturn(List.of());
        when(contacts.find(any(), any())).thenReturn(Optional.empty());
        when(contacts.find(any(), org.mockito.ArgumentMatchers.eq(ANNA)))
            .thenReturn(Optional.of(new ContactDetails("Anna", "Kowalska", null, null)));
    }

    @Test
    void thescreenNamesAllSixColumnsTheDesignAsksFor() throws Exception {
        assertThat(render())
            .contains("Najemca").contains("Lokal").contains("Okres")
            .contains("Czynsz").contains("Saldo").contains("Status");
    }

    /** One row, every cell: the name resolved from contacts, the unit, the term, the money. */
    @Test
    void arowCarriesTheTenantTheLokalTheTermAndTheRent() throws Exception {
        var tenancyId = UUID.randomUUID();
        when(register.forWorkspace(any())).thenReturn(List.of(row(tenancyId, LocalDate.of(2027, 8, 31))));

        var html = render();

        assertThat(html).contains("Anna Kowalska");
        assertThat(html).as("initials for the avatar").contains(">AK<");
        assertThat(html).contains("Marszałkowska 12 · m. 1");
        assertThat(html).contains("01.09.2026 – 31.08.2027");
        assertThat(html).as("the row links to the timeline that already exists")
            .contains("/tenancies/" + tenancyId + "/timeline");
    }

    /**
     * Saldo is signed and owing shows a minus, which is the design's own convention
     * ({@code bal: -4120}) and a ledger's.
     *
     * <p>Accounting reports outstanding as a POSITIVE figure, so the negation is a real step the
     * controller takes and not a pass-through. A regression that dropped it would render a debt as
     * a credit — the same number, the opposite meaning, and nothing else on the row would look
     * wrong.
     */
    @Test
    void adebtRendersNegativeAndInTheOwedStyle() throws Exception {
        var tenancyId = UUID.randomUUID();
        when(register.forWorkspace(any())).thenReturn(List.of(row(tenancyId, null)));
        when(invoices.outstandingByTenancy(any())).thenReturn(Map.of(tenancyId, new BigDecimal("4120")));

        var html = render();

        assertThat(html).contains("-4 120,00");
        assertThat(html).contains("amount--owed");
        assertThat(html).doesNotContain("amount--settled");
    }

    /** Nothing owed is quiet, not red, and carries no sign. */
    @Test
    void asettledTenancyRendersZeroInTheFaintStyle() throws Exception {
        when(register.forWorkspace(any())).thenReturn(List.of(row(UUID.randomUUID(), null)));

        var html = render();

        assertThat(html).contains("amount--settled");
        assertThat(html).doesNotContain("amount--owed");
    }

    /** An indefinite tenancy does not free the unit up, and says so rather than showing a dash. */
    @Test
    void anindefiniteTenancyRendersItsOpenEndAsInfinity() throws Exception {
        when(register.forWorkspace(any())).thenReturn(List.of(row(UUID.randomUUID(), null)));

        assertThat(render()).contains("01.09.2026 – ∞");
    }

    /** The colour comes from accounting and is rendered by the shared fragment, never recomputed. */
    @Test
    void thearrearsColourRendersAsTheSamePillTheReportsScreenUses() throws Exception {
        var tenancyId = UUID.randomUUID();
        when(register.forWorkspace(any())).thenReturn(List.of(row(tenancyId, null)));
        when(board.forWorkspace(any())).thenReturn(List.of(
            new ArrearsBoardProjection.Row(tenancyId, ArrearsColour.BRIGHT_RED, 3)));

        assertThat(render()).contains("pill--critical").contains("Cały okres bez zapłaty");
    }

    /**
     * A tenancy the arrears board has not spoken about renders NO pill — and, more importantly,
     * does not throw.
     *
     * <p>The guard sits on a wrapper rather than beside its own {@code th:replace}, because
     * Thymeleaf resolves {@code th:replace} at precedence 100 and {@code th:if} at 300: on one
     * element the fragment substitutes before the guard is evaluated, so {@code colour().name()}
     * would run on null and take the whole page down with a 500. Written wrong here first. Asserting
     * the 200 is the load-bearing half of this test; the absent pill is the visible half.
     */
    @Test
    void atenancyWithNoArrearsRowRendersNoPillAndStillReturns200() throws Exception {
        when(register.forWorkspace(any())).thenReturn(List.of(row(UUID.randomUUID(), null)));

        var html = render();

        assertThat(html).doesNotContain("pill--critical").doesNotContain("pill--danger");
        assertThat(html).doesNotContain("Nic nie zalega");
    }

    /** Rows written before pm_tenancy_party existed name nobody, and say so in words. */
    @Test
    void atenancyWithNoPartiesReadsAsBezNajemcy() throws Exception {
        when(register.forWorkspace(any())).thenReturn(List.of(
            new TenancyBoardRow(UUID.randomUUID(), UUID.randomUUID(), "m. 4", "Puławska 5",
                Tenancy.State.ACTIVE, LocalDate.of(2026, 9, 1), null, new BigDecimal("2400"),
                List.of())));

        assertThat(render()).contains("Bez najemcy");
    }

    /** A new agency sees an empty state, not an empty card. */
    @Test
    void anagencyWithNoTenanciesSeesTheEmptyState() throws Exception {
        assertThat(render()).contains("Ta agencja nie ma jeszcze żadnych najmów.");
    }

    /**
     * The register is reachable from the rail, which is the whole point of the task: the item was a
     * {@code <span class="nav__item--unbuilt">} and could not be clicked at all.
     */
    @Test
    void thesidebarLinksToTheRegisterAndMarksItActive() throws Exception {
        var html = render();

        assertThat(html).contains("href=\"/tenancies\"");
        assertThat(html).as("the rail item is lit while the register is open")
            .contains("nav__item nav__item--active");
        assertThat(html).as("Najmy is no longer one of the unbuilt spans")
            .doesNotContain("nav__item--unbuilt\" title=\"Najmy\"");
    }

    private String render() throws Exception {
        return mvc.perform(get("/tenancies"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private static TenancyBoardRow row(UUID tenancyId, LocalDate endDate) {
        return new TenancyBoardRow(tenancyId, UUID.randomUUID(), "m. 1", "Marszałkowska 12",
            Tenancy.State.ACTIVE, LocalDate.of(2026, 9, 1), endDate, new BigDecimal("3200"),
            List.of(ANNA));
    }
}
