package pl.najem.acc.application;

import pl.najem.acc.adapter.persistence.PostgresAccounting;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.acc.AccEventTypes;
import pl.najem.acc.TestWorkspace;
import pl.najem.acc.adapter.pm.TenancyActivatedHandler;
import pl.najem.acc.domain.WarningKind;
import pl.najem.contracts.events.TenancyActivatedEvent;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.eventstore.JdbcEventStore;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deposit is charged once, at activation, against a multiplier snapshotted then — because the
 * rent it is a multiple of will move, and the multiple agreed in the contract will not.
 *
 * <p>The statutory caps are warn-gates, never walls: a manager who has a reason to exceed one is
 * doing something the ledger should record and flag, not refuse. The instytucjonalny cap is
 * <strong>six</strong> months' rent (art. 19f ust. 5) — the 3× figure in circulation is wrong, and
 * a wrong cap that warns is worse than none, because it teaches managers to dismiss the warning.
 */
@Testcontainers
class DepositTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate START = LocalDate.of(2027, 8, 1);

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    static WarningService warnings;
    static TenancyActivatedHandler acl;

    @BeforeAll
    static void setUp() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var registry = new EventTypeRegistry();
        AccEventTypes.register(registry);
        var store = new JdbcEventStore(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()), registry);
        warnings = new WarningService(jdbc);
        var ledger = PostgresAccounting.ledgerService(store, jdbc, warnings);
        acl = new TenancyActivatedHandler(ledger, new DepositService(store, jdbc, warnings));
    }

    @Test
    void activatingWithADepositChargesItAndRemembersTheMultiplierAgreed() {
        var tenancyId = activate("ZWYKLY", "3000", "6000");

        var deposit = jdbc.queryForMap(
            "select nominal_amount, multiplier, rent_at_charge, state from acc_deposit where tenancy_id = ?",
            tenancyId);
        assertThat((BigDecimal) deposit.get("nominal_amount")).isEqualByComparingTo("6000");
        assertThat((BigDecimal) deposit.get("multiplier")).isEqualByComparingTo("2");
        assertThat((BigDecimal) deposit.get("rent_at_charge")).isEqualByComparingTo("3000");
        assertThat(deposit.get("state")).isEqualTo("charged");
        assertThat(chargedFor(tenancyId, "deposit")).isEqualByComparingTo("6000");
    }

    /** No deposit is a contract term, not a deposit of nothing. A zero charge would be a lie. */
    @Test
    void aTenancyWithoutADepositGetsNoDepositChargeAtAll() {
        var tenancyId = activate("ZWYKLY", "3000", null);

        assertThat(jdbc.queryForObject("select count(*) from acc_deposit where tenancy_id = ?",
            Integer.class, tenancyId)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from acc_charge where tenancy_id = ? and component = 'deposit'
            """, Integer.class, tenancyId)).isZero();
        assertThat(chargedFor(tenancyId, "rent")).isEqualByComparingTo("3000");
    }

    /** Ochrona praw lokatorów art. 6 ust. 1: twelve months' rent for an ordinary tenancy. */
    @Test
    void anOrdinaryTenancyMayTakeTwelveMonthsRentWithoutAWarning() {
        var tenancyId = activate("ZWYKLY", "3000", "36000");

        assertThat(capWarningsFor(tenancyId)).isEmpty();
    }

    @Test
    void anOrdinaryTenancyBeyondTwelveMonthsRentWarnsWithoutRefusing() {
        var tenancyId = activate("ZWYKLY", "3000", "39000");

        assertThat(capWarningsFor(tenancyId)).singleElement()
            .satisfies(w -> assertThat(w.detail()).contains("12"));
        assertThat(chargedFor(tenancyId, "deposit")).isEqualByComparingTo("39000");
    }

    /**
     * The correction that matters: art. 19f ust. 5 sets the instytucjonalny cap at six months, not
     * three. At five months' rent this tenancy is lawful and must not be warned about.
     */
    @Test
    void anInstitutionalTenancyAtFiveMonthsRentIsLawfulAndSilent() {
        var tenancyId = activate("INSTYTUCJONALNY", "3000", "15000");

        assertThat(capWarningsFor(tenancyId)).isEmpty();
    }

    @Test
    void anInstitutionalTenancyBeyondSixMonthsRentWarns() {
        var tenancyId = activate("INSTYTUCJONALNY", "3000", "21000");

        assertThat(capWarningsFor(tenancyId)).singleElement()
            .satisfies(w -> assertThat(w.detail()).contains("6"));
    }

    /** Art. 19a ust. 4: six months for najem okazjonalny. */
    @Test
    void anOccasionalTenancyIsCappedAtSixMonthsRent() {
        var lawful = activate("OKAZJONALNY", "2000", "12000");
        var excessive = activate("OKAZJONALNY", "2000", "14000");

        assertThat(capWarningsFor(lawful)).isEmpty();
        assertThat(capWarningsFor(excessive)).hasSize(1);
    }

    /**
     * legalForm crosses the contract as a String precisely so it can gain values without a
     * versioning trap. An unrecognised one must not silently skip the cap check — the deposit is
     * still charged, and a human is told the ledger could not check it.
     */
    @Test
    void anUnrecognisedLegalFormIsFlaggedRatherThanSilentlyUncapped() {
        var tenancyId = activate("SPOLDZIELCZY_LOKATORSKI", "3000", "99000");

        assertThat(warningsFor(tenancyId, WarningKind.UNKNOWN_LEGAL_FORM)).hasSize(1);
        assertThat(chargedFor(tenancyId, "deposit")).isEqualByComparingTo("99000");
    }

    /**
     * The cap is expressed as a multiple of the czynsz, so a contract with no czynsz has no cap that
     * can be computed. Returning a multiplier of zero made that read as a successful check — zero is
     * not greater than any cap, so no warning was ever raised and the activation was silent.
     *
     * <p>It is the legally interesting case rather than an obscure one: putting the whole monthly
     * into adminFee and mediaAdvance and declaring no rent is exactly the shape someone would use to
     * take a deposit the cap could not touch, and silence reads as approval.
     */
    @Test
    void aDepositAgainstAZeroCzynszSaysTheCapCouldNotBeCheckedRatherThanNothing() {
        var tenancyId = UUID.randomUUID();
        acl.handle(new TenancyActivatedEvent(WS, tenancyId, UUID.randomUUID(), START,
            new BigDecimal("800"), true, BigDecimal.ZERO, new BigDecimal("500"),
            new BigDecimal("300"), "ZWYKLY", new BigDecimal("20000"), "NAJEM/D9/2027"));

        assertThat(warningsFor(tenancyId, WarningKind.DEPOSIT_CAP_UNCHECKABLE)).singleElement()
            .satisfies(w -> assertThat(w.detail()).contains("20000").contains("czynsz"));
        assertThat(capWarningsFor(tenancyId)).isEmpty();
        assertThat(chargedFor(tenancyId, "deposit")).isEqualByComparingTo("20000");
        // Absent, not zero. Valorization at return works from this base, and a stored zero would
        // read to it as a genuinely computed multiple of nothing.
        assertThat(jdbc.queryForObject("select multiplier from acc_deposit where tenancy_id = ?",
            BigDecimal.class, tenancyId)).isNull();
    }

    /** A cap that could be checked must not also claim it could not. */
    @Test
    void anOrdinaryDepositDoesNotClaimTheCapWasUncheckable() {
        var tenancyId = activate("ZWYKLY", "3000", "6000");

        assertThat(warningsFor(tenancyId, WarningKind.DEPOSIT_CAP_UNCHECKABLE)).isEmpty();
    }

    /**
     * The multiple is of the czynsz, not of the whole monthly figure — adminFee and mediaAdvance are
     * not rent, and valorization at return works from the same base (DEPOSIT §1).
     */
    @Test
    void theMultiplierIsTakenFromTheRentComponentNotTheMonthlyTotal() {
        var tenancyId = UUID.randomUUID();
        acl.handle(new TenancyActivatedEvent(WS, tenancyId, UUID.randomUUID(), START,
            new BigDecimal("3000"), true, new BigDecimal("2400"), new BigDecimal("300"),
            new BigDecimal("300"), "ZWYKLY", new BigDecimal("4800"), "NAJEM/D8/2027"));

        var deposit = jdbc.queryForMap(
            "select multiplier, rent_at_charge from acc_deposit where tenancy_id = ?", tenancyId);
        assertThat((BigDecimal) deposit.get("rent_at_charge")).isEqualByComparingTo("2400");
        assertThat((BigDecimal) deposit.get("multiplier")).isEqualByComparingTo("2");
    }

    private static UUID activate(String legalForm, String monthlyTotal, String depositAmount) {
        var tenancyId = UUID.randomUUID();
        acl.handle(new TenancyActivatedEvent(WS, tenancyId, UUID.randomUUID(), START,
            new BigDecimal(monthlyTotal), false, null, null, null, legalForm,
            depositAmount == null ? null : new BigDecimal(depositAmount),
            "NAJEM/" + tenancyId.toString().substring(0, 8) + "/2027"));
        return tenancyId;
    }

    private static BigDecimal chargedFor(UUID tenancyId, String component) {
        return jdbc.queryForObject("""
            select coalesce(sum(amount), 0) from acc_charge where tenancy_id = ? and component = ?
            """, BigDecimal.class, tenancyId, component);
    }

    private static List<Warning> capWarningsFor(UUID tenancyId) {
        return warningsFor(tenancyId, WarningKind.DEPOSIT_CAP_EXCEEDED);
    }

    private static List<Warning> warningsFor(UUID tenancyId, WarningKind kind) {
        return warnings.unseen(WS).stream()
            .filter(w -> tenancyId.equals(w.tenancyId()) && w.kind() == kind)
            .toList();
    }
}
