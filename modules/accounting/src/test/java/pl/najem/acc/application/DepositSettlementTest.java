package pl.najem.acc.application;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.domain.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Giving the deposit back.
 *
 * <p>Art. 6 ust. 4 u.o.p.l.: the deposit is returned in the amount corresponding to the agreed
 * multiple of the czynsz <em>in force on the day of return</em>, less whatever the landlord is
 * lawfully owed, and never less than the sum actually taken. The multiple comes from the snapshot
 * made at activation rather than from dividing today's figures — the rent moves over a tenancy and
 * the agreed multiple does not.
 *
 * <p>The valorized amount, the deductions and the returned amount are all recorded separately. A
 * tenant who owed nothing and a tenant whose arrears consumed the whole deposit both receive zero,
 * and the difference between those two is the entire dispute.
 */
@Testcontainers
@Tag("integration")
class DepositSettlementTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate START = LocalDate.of(2027, 1, 10);
    private static final LocalDate RETURN = LocalDate.of(2029, 1, 10);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static JdbcEventStore store;
    static DepositService deposits;
    static InvoiceService invoicing;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        var warnings = PostgresAccounting.warningService(jdbc);
        deposits = new DepositService(store, jdbc, warnings);
        invoicing = PostgresAccounting.invoiceService(store, jdbc, warnings);
    }

    /**
     * Rent rose over the tenancy, so the deposit goes back at the new rent. Two months agreed at
     * 3000 zł is 6000 zł taken; two months of 4000 zł is 8000 zł returned. The extra 2000 zł is the
     * inflation the statute puts on the landlord rather than the tenant.
     */
    @Test
    void aDepositGoesBackAtTheRentInForceOnTheDayItIsReturned() {
        var tenancyId = chargedDeposit("6000", "3000", "S1");
        pay(tenancyId);

        deposits.settle(WS, tenancyId, new BigDecimal("4000"), RETURN);

        var settled = settlement(tenancyId);
        assertThat((BigDecimal) settled.get("valorized_amount")).isEqualByComparingTo("8000");
        assertThat((BigDecimal) settled.get("returned_amount")).isEqualByComparingTo("8000");
        assertThat((BigDecimal) settled.get("deducted_amount")).isEqualByComparingTo("0");
        assertThat(settled.get("state")).isEqualTo("settled");
    }

    /**
     * The floor is one-directional. Rent fell, so valorization would return less than the tenant
     * actually paid, and the statute does not allow that.
     */
    @Test
    void aFallenRentNeverReturnsLessThanWasTaken() {
        var tenancyId = chargedDeposit("6000", "3000", "S2");
        pay(tenancyId);

        deposits.settle(WS, tenancyId, new BigDecimal("2000"), RETURN);

        assertThat((BigDecimal) settlement(tenancyId).get("returned_amount"))
            .isEqualByComparingTo("6000");
    }

    /** What the landlord is owed comes out of it, and what is left goes back. */
    @Test
    void arrearsAreDeductedAndOnlyTheRemainderIsReturned() {
        var tenancyId = chargedDeposit("6000", "3000", "S3");
        pay(tenancyId);
        invoicing.postRent(WS, tenancyId, new BigDecimal("3000"), START.plusYears(1), "NAJEM/S3/2028");

        deposits.settle(WS, tenancyId, new BigDecimal("3000"), RETURN);

        var settled = settlement(tenancyId);
        assertThat((BigDecimal) settled.get("valorized_amount")).isEqualByComparingTo("6000");
        assertThat((BigDecimal) settled.get("deducted_amount")).isEqualByComparingTo("3000");
        assertThat((BigDecimal) settled.get("returned_amount")).isEqualByComparingTo("3000");
    }

    /**
     * Deductions are itemised against the charges they settled. A disputed deduction has to be
     * traceable to an obligation, not appear as one unexplained total.
     */
    @Test
    void everyDeductionNamesTheChargeItPaid() {
        var tenancyId = chargedDeposit("6000", "3000", "S4");
        pay(tenancyId);
        invoicing.postRent(WS, tenancyId, new BigDecimal("1000"), START.plusYears(1), "NAJEM/S4A/2028");
        invoicing.postRent(WS, tenancyId, new BigDecimal("2000"), START.plusMonths(13), "NAJEM/S4B/2028");

        deposits.settle(WS, tenancyId, new BigDecimal("3000"), RETURN);

        var deductions = jdbc.queryForList("""
            select amount from acc_deposit_deduction where deposit_id =
                (select deposit_id from acc_deposit where tenancy_id = ?) order by amount
            """, BigDecimal.class, tenancyId);
        assertThat(deductions).containsExactly(new BigDecimal("1000.00"), new BigDecimal("2000.00"));
        assertThat(openOn(tenancyId)).isEqualByComparingTo("0");
    }

    /** Arrears beyond the deposit are not forgiven by settling it; they stay owed. */
    @Test
    void arrearsLargerThanTheDepositLeaveTheRemainderStillOwed() {
        var tenancyId = chargedDeposit("6000", "3000", "S5");
        pay(tenancyId);
        invoicing.postRent(WS, tenancyId, new BigDecimal("9000"), START.plusYears(1), "NAJEM/S5/2028");

        deposits.settle(WS, tenancyId, new BigDecimal("3000"), RETURN);

        var settled = settlement(tenancyId);
        assertThat((BigDecimal) settled.get("deducted_amount")).isEqualByComparingTo("6000");
        assertThat((BigDecimal) settled.get("returned_amount")).isEqualByComparingTo("0");
        assertThat(openOn(tenancyId)).isEqualByComparingTo("3000");
    }

    /**
     * A deposit that was charged but never paid is not money the landlord holds. There is nothing to
     * valorize and nothing to give back, and returning a figure for it would invent funds.
     */
    @Test
    void aDepositThatWasNeverPaidCannotBeReturned() {
        var tenancyId = chargedDeposit("6000", "3000", "S6");

        assertThatThrownBy(() -> deposits.settle(WS, tenancyId, new BigDecimal("3000"), RETURN))
            .isInstanceOf(DepositNotHeldException.class);
    }

    /** Returning it twice would pay the tenant twice. */
    @Test
    void aDepositCannotBeSettledTwice() {
        var tenancyId = chargedDeposit("6000", "3000", "S7");
        pay(tenancyId);
        deposits.settle(WS, tenancyId, new BigDecimal("3000"), RETURN);

        assertThatThrownBy(() -> deposits.settle(WS, tenancyId, new BigDecimal("3000"), RETURN))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * The multiple was never computable for this tenancy — no czynsz was declared, which is why
     * activation raised DEPOSIT_CAP_UNCHECKABLE. Valorization cannot invent one, so the nominal
     * floor is what goes back, and that is the honest answer rather than a computed zero.
     */
    @Test
    void aDepositWithNoAgreedMultipleGoesBackAtItsNominalValue() {
        var tenancyId = UUID.randomUUID();
        deposits.chargeOnActivation(WS, tenancyId, new BigDecimal("5000"), BigDecimal.ZERO,
            "ZWYKLY", START, "NAJEM/S8/2027");
        pay(tenancyId);

        deposits.settle(WS, tenancyId, new BigDecimal("4000"), RETURN);

        assertThat((BigDecimal) settlement(tenancyId).get("returned_amount"))
            .isEqualByComparingTo("5000");
    }

    /**
     * A redelivered activation does not charge the deposit a second time. The outbox is
     * at-least-once, so the redelivery itself is expected; what must not happen is a tenancy ending
     * up with two deposits of different amounts and no lawful way to choose which one goes back.
     * The database refuses it, which is why {@code held} can take the single row without a tie-break.
     */
    @Test
    void aRedeliveredActivationCannotChargeASecondDeposit() {
        var tenancyId = chargedDeposit("6000", "3000", "S10");

        assertThatThrownBy(() -> deposits.chargeOnActivation(WS, tenancyId, new BigDecimal("9000"),
            new BigDecimal("4500"), "ZWYKLY", START, "KAUCJA/S10B/2027"))
            .isInstanceOf(DuplicateKeyException.class);

        assertThat(jdbc.queryForObject("select count(*) from acc_deposit where tenancy_id = ?",
            Integer.class, tenancyId)).isEqualTo(1);
    }

    /** A settlement belongs to one workspace; another may not reach it. */
    @Test
    void aDepositCannotBeSettledFromAnotherWorkspace() {
        var tenancyId = chargedDeposit("6000", "3000", "S9");
        pay(tenancyId);

        assertThatThrownBy(() -> deposits.settle(UUID.randomUUID(), tenancyId,
            new BigDecimal("3000"), RETURN)).isInstanceOf(DepositNotHeldException.class);
    }

    private static UUID chargedDeposit(String amount, String rent, String ref) {
        var tenancyId = UUID.randomUUID();
        deposits.chargeOnActivation(WS, tenancyId, new BigDecimal(amount), new BigDecimal(rent),
            "ZWYKLY", START, "KAUCJA/" + ref + "/2027");
        return tenancyId;
    }

    /** The tenant actually transferred the deposit, so the landlord is holding it. */
    private static void pay(UUID tenancyId) {
        jdbc.update("""
            update acc_charge set allocated_amount = amount, allocated = true
            where workspace_id = ? and tenancy_id = ? and component = ?
            """, WS, tenancyId, Component.DEPOSIT.wireName());
    }

    private static Map<String, Object> settlement(UUID tenancyId) {
        return jdbc.queryForMap("""
            select state, valorized_amount, deducted_amount, returned_amount, rent_at_return
            from acc_deposit where tenancy_id = ?
            """, tenancyId);
    }

    /** What the tenancy still owes on charges that are not the deposit itself. */
    private static BigDecimal openOn(UUID tenancyId) {
        return jdbc.queryForObject("""
            select coalesce(sum(amount - allocated_amount), 0) from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and component <> ?
            """, BigDecimal.class, WS, tenancyId, Component.DEPOSIT.wireName());
    }
}
