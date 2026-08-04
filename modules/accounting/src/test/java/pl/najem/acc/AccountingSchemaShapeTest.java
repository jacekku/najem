package pl.najem.acc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of accounting's schema, asserted against the schema itself rather than against
 * behaviour.
 *
 * <p>This exists for the migration squash (vote closed at seq 255, condition 2). A green
 * behavioural suite does not verify a baseline: every functional test in this module provisions a
 * handful of rows, and at ten rows a dropped index is invisible, a dropped {@code not null} is
 * invisible, and a dropped unique constraint is invisible until two tenants collide in production.
 * A baseline generated from {@code pg_dump} of a migrated database should be identical by
 * construction — this is what checks that it is, and it must be green <em>before</em> anything is
 * deleted, because a test written afterwards can only assert that the squash matches itself.
 *
 * <p>The three snapshots are deliberately complete rather than a selection. The risk in a squash is
 * not that a constraint changes, it is that one silently disappears, and only a total assertion
 * catches a disappearance. They are expected to be edited by hand whenever a migration lands:
 * that friction is the point, since a schema change that nobody had to write down is exactly the
 * kind that gets squashed away.
 */
@Testcontainers
class AccountingSchemaShapeTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/acc").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void everyColumnKeepsItsTypeNullabilityAndDefault() {
        assertThat(actual("""
            select table_name || '.' || column_name || ' ' || data_type
                 || case when is_nullable = 'NO' then ' not null' else ' null' end
                 || coalesce(' default ' || column_default, '')
            from information_schema.columns
            where table_schema = 'public' and table_name like 'acc%'
            """)).containsExactlyElementsOf(EXPECTED_COLUMNS);
    }

    @Test
    void everyKeyAndForeignKeySurvives() {
        assertThat(actual("""
            select conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
            from pg_constraint
            where connamespace = 'public'::regnamespace and conrelid::regclass::text like 'acc%'
            """)).containsExactlyElementsOf(EXPECTED_CONSTRAINTS);
    }

    /**
     * Indexes are the easiest thing to lose in a squash and the hardest to notice: nothing fails,
     * the module simply gets slower as the tables grow. Every one of these is on a query this
     * module runs on every ingestion or every board render.
     */
    @Test
    void everyIndexSurvives() {
        assertThat(actual("""
            select tablename || ' ' || indexname || ' ' || replace(indexdef, 'public.', '')
            from pg_indexes where schemaname = 'public' and tablename like 'acc%'
            """)).containsExactlyElementsOf(EXPECTED_INDEXES);
    }

    /**
     * The constraints whose <em>reasoning</em> has to survive the squash, not merely their DDL
     * (condition 3). Each of these was a defect before it was a constraint, and each would still
     * pass this module's behavioural suite if it were dropped.
     */
    @Test
    void theLoadBearingConstraintsAreTheOnesTheyWereMadeInto() {
        // A bank line's external id is unique only within the feed it came from, and feeds belong
        // to workspaces: two agencies may legitimately see the same id without it being the same
        // payment. This started as a global unique key, which silently discarded the second
        // agency's payment as a duplicate. Scoped by workspace, never globally.
        assertThat(constraintOn("acc_payment", "acc_payment_workspace_external_id_key"))
            .isEqualTo("UNIQUE (workspace_id, external_id)");

        // The remembered-payer tier (ladder tier 3) matches an incoming IBAN to a tenancy. Keyed on
        // the IBAN alone it could not hold a guarantor paying for two tenancies -- the second
        // learning overwrote the first, and the ladder then confidently matched the wrong tenancy.
        // Tenancy is part of the key so both are remembered, and tier 3 declines when it is
        // ambiguous rather than guessing.
        assertThat(constraintOn("acc_payer_account", "acc_payer_account_pkey"))
            .isEqualTo("PRIMARY KEY (workspace_id, counterparty_iban, tenancy_id)");

        // One deposit per tenancy. The deposit is charged once, at activation, against a multiplier
        // snapshotted then; a second row would mean a second snapshot and no way to tell which one
        // valorization at return should work from.
        assertThat(constraintOn("acc_deposit", "acc_deposit_workspace_id_tenancy_id_key"))
            .isEqualTo("UNIQUE (workspace_id, tenancy_id)");
    }

    /**
     * Defaults that are load-bearing rather than cosmetic: each one is what an existing row got
     * when its column was added, and each is read as a fact by the code.
     */
    @Test
    void theDefaultsThatCarryMeaningSurvive() {
        // Charges predating the lifecycle migration are active and unpaid. Were `active` to default
        // false, every historical charge would vanish from the board at once and the portfolio
        // would read as owing nothing.
        assertThat(defaultOf("acc_charge", "active")).isEqualTo("true");
        assertThat(defaultOf("acc_charge", "allocated_amount")).isEqualTo("0");

        // Payments predating the detail migration are incoming złoty, which is what the module
        // handled before it could express anything else. Debits and non-PLN are never suggested
        // against a charge, so a wrong default here would put foreign money on Polish rent.
        assertThat(defaultOf("acc_payment", "direction")).isEqualTo("'CRDT'::text");
        assertThat(defaultOf("acc_payment", "currency")).isEqualTo("'PLN'::text");

        // An allocation is live until something reverses it; a reversal marks the row rather than
        // deleting it, because the ledger does not delete facts it has asserted.
        assertThat(defaultOf("acc_allocation", "reversed")).isEqualTo("false");
    }

    private static List<String> actual(String sql) {
        return jdbc.queryForList(sql + " order by 1", String.class);
    }

    private static String constraintOn(String table, String name) {
        return jdbc.queryForObject("""
            select pg_get_constraintdef(oid) from pg_constraint
            where conrelid = ?::regclass and conname = ?
            """, String.class, table, name);
    }

    private static String defaultOf(String table, String column) {
        return jdbc.queryForObject("""
            select column_default from information_schema.columns
            where table_schema = 'public' and table_name = ? and column_name = ?
            """, String.class, table, column);
    }

    private static final List<String> EXPECTED_COLUMNS = List.of(
        "acc_allocation.allocated_on date not null",
        "acc_allocation.allocation_id uuid not null",
        "acc_allocation.amount numeric not null",
        "acc_allocation.charge_id uuid not null",
        "acc_allocation.component text not null",
        "acc_allocation.payment_id uuid not null",
        "acc_allocation.reversed boolean not null default false",
        "acc_allocation.tenancy_id uuid not null",
        "acc_allocation.workspace_id uuid not null",
        "acc_charge.active boolean not null default true",
        "acc_charge.allocated boolean not null default false",
        "acc_charge.allocated_amount numeric not null default 0",
        "acc_charge.amount numeric not null",
        "acc_charge.charge_id uuid not null",
        "acc_charge.component text not null",
        "acc_charge.due_date date not null",
        "acc_charge.payment_reference text not null",
        "acc_charge.tenancy_id uuid not null",
        "acc_charge.workspace_id uuid not null",
        "acc_credit_note.amount numeric not null",
        "acc_credit_note.charge_id uuid not null",
        "acc_credit_note.credit_note_id uuid not null",
        "acc_credit_note.issued_on date not null",
        "acc_credit_note.reason text not null",
        "acc_credit_note.tenancy_id uuid not null",
        "acc_credit_note.workspace_id uuid not null",
        "acc_deposit.charge_id uuid not null",
        "acc_deposit.charged_on date not null",
        "acc_deposit.deposit_id uuid not null",
        "acc_deposit.legal_form text not null",
        "acc_deposit.multiplier numeric not null",
        "acc_deposit.nominal_amount numeric not null",
        "acc_deposit.rent_at_charge numeric not null",
        "acc_deposit.state text not null",
        "acc_deposit.tenancy_id uuid not null",
        "acc_deposit.workspace_id uuid not null",
        "acc_payer_account.counterparty_iban text not null",
        "acc_payer_account.learned_from uuid not null",
        "acc_payer_account.learned_on date not null",
        "acc_payer_account.tenancy_id uuid not null",
        "acc_payer_account.workspace_id uuid not null",
        "acc_payment.amount numeric not null",
        "acc_payment.bank_reference text null",
        "acc_payment.booking_date date not null",
        "acc_payment.classified_on date null",
        "acc_payment.counterparty_iban text null",
        "acc_payment.counterparty_name text null",
        "acc_payment.currency text not null default 'PLN'::text",
        "acc_payment.direction text not null default 'CRDT'::text",
        "acc_payment.external_id text not null",
        "acc_payment.non_tenant_reason text null",
        "acc_payment.payment_id uuid not null",
        "acc_payment.reversal_reason text null",
        "acc_payment.reversed_on date null",
        "acc_payment.status text not null",
        "acc_payment.title text not null",
        "acc_payment.unallocated_amount numeric not null",
        "acc_payment.value_date date null",
        "acc_payment.workspace_id uuid not null",
        "acc_suggestion.charge_id uuid not null",
        "acc_suggestion.payment_id uuid not null",
        "acc_suggestion.tier integer not null default 1",
        "acc_suggestion.workspace_id uuid not null",
        "acc_tenancy_status.status text not null",
        "acc_tenancy_status.tenancy_id uuid not null",
        "acc_tenancy_status.workspace_id uuid not null",
        "acc_warning.detail text not null",
        "acc_warning.kind text not null",
        "acc_warning.raised_at timestamp with time zone not null default now()",
        "acc_warning.seen boolean not null default false",
        "acc_warning.tenancy_id uuid not null",
        "acc_warning.warning_id uuid not null",
        "acc_warning.workspace_id uuid not null");

    private static final List<String> EXPECTED_CONSTRAINTS = List.of(
        "acc_allocation acc_allocation_charge_id_fkey FOREIGN KEY (charge_id) REFERENCES acc_charge(charge_id)",
        "acc_allocation acc_allocation_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES acc_payment(payment_id)",
        "acc_allocation acc_allocation_pkey PRIMARY KEY (allocation_id)",
        "acc_charge acc_charge_pkey PRIMARY KEY (charge_id)",
        "acc_credit_note acc_credit_note_charge_id_fkey FOREIGN KEY (charge_id) REFERENCES acc_charge(charge_id)",
        "acc_credit_note acc_credit_note_pkey PRIMARY KEY (credit_note_id)",
        "acc_deposit acc_deposit_charge_id_fkey FOREIGN KEY (charge_id) REFERENCES acc_charge(charge_id)",
        "acc_deposit acc_deposit_pkey PRIMARY KEY (deposit_id)",
        "acc_deposit acc_deposit_workspace_id_tenancy_id_key UNIQUE (workspace_id, tenancy_id)",
        "acc_payer_account acc_payer_account_pkey PRIMARY KEY (workspace_id, counterparty_iban, tenancy_id)",
        "acc_payment acc_payment_pkey PRIMARY KEY (payment_id)",
        "acc_payment acc_payment_workspace_external_id_key UNIQUE (workspace_id, external_id)",
        "acc_suggestion acc_suggestion_charge_id_fkey FOREIGN KEY (charge_id) REFERENCES acc_charge(charge_id)",
        "acc_suggestion acc_suggestion_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES acc_payment(payment_id)",
        "acc_suggestion acc_suggestion_pkey PRIMARY KEY (payment_id)",
        "acc_tenancy_status acc_tenancy_status_pkey PRIMARY KEY (tenancy_id)",
        "acc_warning acc_warning_pkey PRIMARY KEY (warning_id)");

    private static final List<String> EXPECTED_INDEXES = List.of(
        "acc_allocation acc_allocation_charge_idx CREATE INDEX acc_allocation_charge_idx ON acc_allocation USING btree (workspace_id, charge_id)",
        "acc_allocation acc_allocation_payment_idx CREATE INDEX acc_allocation_payment_idx ON acc_allocation USING btree (workspace_id, payment_id)",
        "acc_allocation acc_allocation_pkey CREATE UNIQUE INDEX acc_allocation_pkey ON acc_allocation USING btree (allocation_id)",
        "acc_allocation acc_allocation_tenancy_idx CREATE INDEX acc_allocation_tenancy_idx ON acc_allocation USING btree (workspace_id, tenancy_id)",
        "acc_charge acc_charge_pkey CREATE UNIQUE INDEX acc_charge_pkey ON acc_charge USING btree (charge_id)",
        "acc_charge acc_charge_workspace_idx CREATE INDEX acc_charge_workspace_idx ON acc_charge USING btree (workspace_id)",
        "acc_credit_note acc_credit_note_charge_idx CREATE INDEX acc_credit_note_charge_idx ON acc_credit_note USING btree (charge_id)",
        "acc_credit_note acc_credit_note_pkey CREATE UNIQUE INDEX acc_credit_note_pkey ON acc_credit_note USING btree (credit_note_id)",
        "acc_credit_note acc_credit_note_workspace_idx CREATE INDEX acc_credit_note_workspace_idx ON acc_credit_note USING btree (workspace_id)",
        "acc_deposit acc_deposit_pkey CREATE UNIQUE INDEX acc_deposit_pkey ON acc_deposit USING btree (deposit_id)",
        "acc_deposit acc_deposit_workspace_id_tenancy_id_key CREATE UNIQUE INDEX acc_deposit_workspace_id_tenancy_id_key ON acc_deposit USING btree (workspace_id, tenancy_id)",
        "acc_payer_account acc_payer_account_pkey CREATE UNIQUE INDEX acc_payer_account_pkey ON acc_payer_account USING btree (workspace_id, counterparty_iban, tenancy_id)",
        "acc_payment acc_payment_counterparty_idx CREATE INDEX acc_payment_counterparty_idx ON acc_payment USING btree (workspace_id, counterparty_iban)",
        "acc_payment acc_payment_pkey CREATE UNIQUE INDEX acc_payment_pkey ON acc_payment USING btree (payment_id)",
        "acc_payment acc_payment_suspense_idx CREATE INDEX acc_payment_suspense_idx ON acc_payment USING btree (workspace_id, status)",
        "acc_payment acc_payment_workspace_external_id_key CREATE UNIQUE INDEX acc_payment_workspace_external_id_key ON acc_payment USING btree (workspace_id, external_id)",
        "acc_payment acc_payment_workspace_idx CREATE INDEX acc_payment_workspace_idx ON acc_payment USING btree (workspace_id)",
        "acc_suggestion acc_suggestion_pkey CREATE UNIQUE INDEX acc_suggestion_pkey ON acc_suggestion USING btree (payment_id)",
        "acc_tenancy_status acc_tenancy_status_pkey CREATE UNIQUE INDEX acc_tenancy_status_pkey ON acc_tenancy_status USING btree (tenancy_id)",
        "acc_tenancy_status acc_tenancy_status_workspace_idx CREATE INDEX acc_tenancy_status_workspace_idx ON acc_tenancy_status USING btree (workspace_id)",
        "acc_warning acc_warning_pkey CREATE UNIQUE INDEX acc_warning_pkey ON acc_warning USING btree (warning_id)",
        "acc_warning acc_warning_unseen_idx CREATE INDEX acc_warning_unseen_idx ON acc_warning USING btree (workspace_id, seen)");
}
