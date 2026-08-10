package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InvoiceRepository#outstandingByTenancy} — the Saldo column of the Najmy register, without
 * a database.
 *
 * <p>Two tiers, and this is the cheap one. When they disagree, <strong>{@code TenancyBalanceTest}
 * is right and this one is wrong</strong>.
 *
 * <p>Every assertion here is about the <em>predicate</em> rather than about arithmetic: what counts
 * as still owed, and what stops counting. Summing is not where this goes wrong — deciding which
 * charges belong in the sum is, and it is the same decision {@code openInvoices} makes separately.
 */
class TenancyBalanceInMemoryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate DUE = LocalDate.of(2027, 1, 10);

    private InMemoryInvoiceRepository invoices;

    @BeforeEach
    void setUp() {
        invoices = new InMemoryInvoiceRepository();
    }

    @Test
    void everyOpenChargeOfATenancyAddsUpIntoOneFigure() {
        var tenancyId = UUID.randomUUID();
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2500"), DUE);
        invoices.post(WS, tenancyId, Component.MEDIA_ADVANCE, new BigDecimal("400"), DUE);

        assertThat(invoices.outstandingByTenancy(WS))
            .containsOnlyKeys(tenancyId)
            .extractingByKey(tenancyId, org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
            .isEqualByComparingTo("2900");
    }

    /** Two tenancies do not pool. The whole point of the map is that each one owes its own. */
    @Test
    void eachTenancyGetsItsOwnTotal() {
        var anna = UUID.randomUUID();
        var karol = UUID.randomUUID();
        invoices.post(WS, anna, Component.RENT, new BigDecimal("2500"), DUE);
        invoices.post(WS, karol, Component.RENT, new BigDecimal("3100"), DUE);

        assertThat(invoices.outstandingByTenancy(WS))
            .containsOnlyKeys(anna, karol)
            .containsEntry(anna, new BigDecimal("2500"))
            .containsEntry(karol, new BigDecimal("3100"));
    }

    /** Part-paid means part-owed, not nothing-owed and not still-everything. */
    @Test
    void apartlySettledChargeContributesOnlyWhatIsLeft() {
        var tenancyId = UUID.randomUUID();
        var invoiceId = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2500"), DUE);

        invoices.applyAllocation(WS, invoiceId, new BigDecimal("1000"));

        assertThat(invoices.outstandingByTenancy(WS)).containsEntry(tenancyId, new BigDecimal("1500"));
    }

    /**
     * A tenancy owing nothing is ABSENT, not present with zero — the port says so rather than
     * leaving the caller to guess, and the screen reads absence as zero in exactly one place.
     */
    @Test
    void afullySettledTenancyDropsOutOfTheMapEntirely() {
        var tenancyId = UUID.randomUUID();
        var invoiceId = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2500"), DUE);

        invoices.applyAllocation(WS, invoiceId, new BigDecimal("2500"));

        assertThat(invoices.outstandingByTenancy(WS)).isEmpty();
    }

    /**
     * A withdrawn charge is not an obligation, so it is not a balance.
     *
     * <p>This is the assertion that earns the port's fragile-by-design note. {@code active} is the
     * one term of the predicate that has nothing to do with money, so a rewrite that reached for
     * "amount not fully allocated" alone would pass every other test in this class and quietly bill
     * a manager for a charge the agency already took back.
     */
    @Test
    void awithdrawnChargeIsNotOwed() {
        var tenancyId = UUID.randomUUID();
        var invoiceId = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2500"), DUE);

        invoices.deactivate(invoiceId);

        assertThat(invoices.outstandingByTenancy(WS)).isEmpty();
    }

    /** Workspace is the hard boundary here as everywhere: a filter, and an empty answer. */
    @Test
    void anotherAgencysChargesAreNotVisible() {
        invoices.post(UUID.randomUUID(), UUID.randomUUID(), Component.RENT,
            new BigDecimal("2500"), DUE);

        assertThat(invoices.outstandingByTenancy(WS)).isEmpty();
    }

    /**
     * The contract this port's javadoc promises: its total for a tenancy equals the sum of the very
     * invoices {@code openInvoices} hands that tenancy's own screen.
     *
     * <p>Asserted rather than trusted, because the two are separate expressions over the same
     * table — one a {@code group by}, one a row query — and the failure mode if they drift is a
     * register saying 2 900 next to a detail page listing 2 500. Both tiers carry this test.
     */
    @Test
    void thetotalAgreesWithTheInvoicesTheOtherReadReturns() {
        var tenancyId = UUID.randomUUID();
        invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("2500"), DUE);
        invoices.post(WS, tenancyId, Component.MEDIA_ADVANCE, new BigDecimal("400"), DUE);
        var withdrawn = invoices.post(WS, tenancyId, Component.ADMIN_FEE, new BigDecimal("120"), DUE);
        invoices.deactivate(withdrawn);
        var partPaid = invoices.post(WS, tenancyId, Component.RENT, new BigDecimal("300"), DUE);
        invoices.applyAllocation(WS, partPaid, new BigDecimal("100"));

        var fromRows = invoices.openInvoices(WS, tenancyId).stream()
            .map(invoice -> invoice.owed())
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(invoices.outstandingByTenancy(WS).get(tenancyId)).isEqualByComparingTo(fromRows);
    }
}
