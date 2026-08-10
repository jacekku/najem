package pl.najem.pm;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of PM's schema, asserted against the schema rather than against behaviour. PM's half of
 * squash condition 2, written to @najem-accounting's {@code AccountingSchemaShapeTest} (`d92da68`).
 *
 * <p>It must be green <em>before</em> anything is deleted: a baseline generated from a migrated
 * database is identical by construction, so a test written afterwards can only assert that the
 * squash matches itself. @najem-accounting proved the behavioural suite cannot stand in for this —
 * they dropped an index and a {@code not null} and all 110 of their tests stayed green. PM's 163
 * would do the same, for the same reason: every functional test here provisions a handful of rows,
 * and at ten rows a dropped index is invisible, a dropped {@code not null} is invisible, and a
 * dropped unique constraint is invisible until two managers collide in production.
 *
 * <p>The snapshots are complete rather than selective, because the risk in a squash is a constraint
 * silently disappearing and only a total assertion catches a disappearance. They must be edited by
 * hand when a migration lands. That friction is the point.
 *
 * <p><b>Two things the dump made visible that were not written down anywhere.</b> PM has
 * <em>no foreign keys at all</em>, where accounting has seven — these are projections rebuilt from
 * events, and a foreign key between two of them would constrain the order a rebuild may replay in.
 * PM has no rebuild path yet (najem-reviewer's outstanding finding), so this is recorded as the
 * current fact rather than defended as a decision. And {@code pm_repair_open_idx} and
 * {@code pm_inspection_due_idx} do not lead with {@code workspace_id}, unlike every other index
 * here, which predates the queries above them growing a workspace predicate.
 */
@Testcontainers
@Tag("integration")
class PmSchemaShapeTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/eventstore", "classpath:db/pm").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void everyColumnKeepsItsTypeNullabilityAndDefault() {
        assertThat(actual("""
            select table_name || '.' || column_name || ' ' || data_type
                 || case when is_nullable = 'YES' then ' null' else ' not null' end
                 || coalesce(' default ' || column_default, '')
            from information_schema.columns
            where table_schema = 'public' and table_name like 'pm%'
            """)).containsExactlyElementsOf(EXPECTED_COLUMNS);
    }

    @Test
    void everyKeySurvives() {
        assertThat(actual("""
            select conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
            from pg_constraint
            where connamespace = 'public'::regnamespace and conrelid::regclass::text like 'pm%'
            """)).containsExactlyElementsOf(EXPECTED_CONSTRAINTS);
    }

    /**
     * Indexes are the easiest thing to lose in a squash and the hardest to notice: nothing fails,
     * the module just gets slower as the portfolio grows. Two of these are partial, and a partial
     * index that comes back total is a silent behaviour change rather than a silent slowdown.
     */
    @Test
    void everyIndexSurvivesIncludingItsPredicate() {
        assertThat(actual("""
            select tablename || ' ' || indexname || ' ' || replace(indexdef, 'public.', '')
            from pg_indexes where schemaname = 'public' and tablename like 'pm%'
            """)).containsExactlyElementsOf(EXPECTED_INDEXES);
    }

    /**
     * The constraints whose <em>reasoning</em> has to survive, not merely their DDL — the vote's
     * condition 3, and the half a {@code pg_dump} cannot carry. Each would still pass PM's whole
     * behavioural suite if it were dropped.
     */
    @Test
    void theLoadBearingConstraintsAreTheOnesTheyWereMadeInto() {
        // One live timer per (kind, subject). A process manager re-arms by upserting on this key,
        // so it is what makes arming idempotent: without it, flagging a tenancy ending-soon twice
        // leaves two rows, the sweeper fires twice, and the manager is told twice about one
        // tenancy. The key is (kind, subject_id) and not (subject_id) because one tenancy carries
        // several unrelated timers -- ending-soon and deposit-settlement -- at the same time.
        assertThat(constraintOn("pm_process_due", "pm_process_due_pkey"))
            .isEqualTo("PRIMARY KEY (kind, subject_id)");

        // pm_process_due is the one PM table with no workspace column, and that is deliberate: a
        // timer's subject is an aggregate whose own workspace is checked when the process manager
        // loads it. A workspace here would be a second copy of a fact the aggregate owns, and two
        // copies of one fact can disagree. WorkspaceBoundaryTest exempts this table by name for
        // the same reason, so if a squash ever adds the column the exemption becomes a lie.
        assertThat(columnsOf("pm_process_due"))
            .containsExactly("due_on", "fired_at", "kind", "subject_id");

        // Role is IN this key, and narrowing it to (tenancy_id, contact_id) is the tempting change
        // that must not be made. The web layer refuses to name one person as both tenant and
        // guarantor on a reservation, but the AGGREGATE does not — so the narrower key would encode
        // an invariant the record has never enforced, and the projection would start throwing on a
        // state PM considers legal. A projection is not the place to introduce a rule the record
        // lacks. The DDL snapshot above would notice the change; only this says why it is wrong.
        assertThat(constraintOn("pm_tenancy_party", "pm_tenancy_party_pkey"))
            .isEqualTo("PRIMARY KEY (tenancy_id, contact_id, role)");
    }

    /**
     * Defaults and nullability that carry meaning. Each is what an existing row got when its column
     * was added, and each is read as a fact by the code above it.
     */
    @Test
    void thedefaultsAndTheAbsenceOfThemBothCarryMeaning() {
        // A unit predating the market migration is INVENTORY -- known about, not advertised. Rule 7
        // in DDL: were this to default to a rentable state, every historical unit would silently
        // become available to let, which is a claim about a landlord's property that nobody made.
        assertThat(defaultOf("pm_unit", "market_state")).isEqualTo("'INVENTORY'::text");

        // These have NO default on purpose and that is the assertion. A tenancy's legal form
        // decides which statutory regime applies -- the deposit cap, the notice periods, whether a
        // notarial declaration exists -- and component_split decides whether the rent is one figure
        // or several. A default here would answer a legal question on the manager's behalf by
        // omission, which is the exact inversion rule 7 forbids. The reservation endpoint refuses
        // a missing legalForm with a 400; this is the same refusal one layer down.
        assertThat(defaultOf("pm_tenancy", "legal_form")).isNull();
        assertThat(defaultOf("pm_tenancy", "component_split")).isNull();

        // Nullable on purpose, and the pair is the point: rent and admin_fee are null when the
        // tenancy declares a single monthly_total rather than a breakdown. Making them not null
        // with a zero default would turn "not broken down" into "zero rent", which is what
        // fa426bf found silently disabling the statutory deposit cap.
        assertThat(nullabilityOf("pm_tenancy", "rent")).isEqualTo("YES");
        assertThat(nullabilityOf("pm_tenancy", "monthly_total")).isEqualTo("NO");
    }

    /**
     * The snapshots above are three assertions that a list equals a list, and a query returning
     * nothing makes all three pass against an empty database — a wrong Flyway location, a filter
     * that stops matching, a container that migrated nothing. najem-reviewer, seq 249, and it has
     * already caught me once today in a different scan.
     */
    @Test
    void themigrationsActuallyRanAndTheFilterMatchesPmsTables() {
        assertThat(EXPECTED_COLUMNS).hasSizeGreaterThan(40);
        assertThat(actual("""
            select table_name from information_schema.tables where table_schema = 'public'
            """)).contains("events", "outbox", "pm_tenancy", "pm_unit", "pm_process_due");
    }

    private static List<String> actual(String sql) {
        return jdbc.queryForList(sql + " order by 1", String.class);
    }

    private static List<String> columnsOf(String table) {
        return jdbc.queryForList("""
            select column_name from information_schema.columns
            where table_schema = 'public' and table_name = ? order by 1
            """, String.class, table);
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

    private static String nullabilityOf(String table, String column) {
        return jdbc.queryForObject("""
            select is_nullable from information_schema.columns
            where table_schema = 'public' and table_name = ? and column_name = ?
            """, String.class, table, column);
    }

    private static final List<String> EXPECTED_COLUMNS = List.of(
        "pm_inspection.findings text null",
        "pm_inspection.inspection_id uuid not null",
        "pm_inspection.next_due_on date not null",
        "pm_inspection.performed_on date not null",
        "pm_inspection.property_id uuid not null",
        "pm_inspection.report_doc text null",
        "pm_inspection.type text not null",
        "pm_inspection.workspace_id uuid not null",
        "pm_process_due.due_on date not null",
        "pm_process_due.fired_at timestamp with time zone null",
        "pm_process_due.kind text not null",
        "pm_process_due.subject_id uuid not null",
        "pm_property.address text not null",
        "pm_property.property_id uuid not null",
        "pm_property.rent_target numeric null",
        "pm_property.workspace_id uuid not null",
        "pm_repair.asset_id uuid not null",
        "pm_repair.caused_by_tenancy uuid null",
        "pm_repair.completed_on date null",
        "pm_repair.description text not null",
        "pm_repair.repair_id uuid not null",
        "pm_repair.reported_on date not null",
        "pm_repair.scope text not null",
        "pm_repair.statutory_duty_hint text not null",
        "pm_repair.workspace_id uuid not null",
        "pm_tenancy.activated_on date null",
        "pm_tenancy.admin_fee numeric null",
        "pm_tenancy.component_split boolean not null",
        "pm_tenancy.deposit_amount numeric null",
        "pm_tenancy.end_date date null",
        "pm_tenancy.end_reason text null",
        "pm_tenancy.insurance_valid_to date null",
        "pm_tenancy.legal_form text not null",
        "pm_tenancy.media_advance numeric null",
        "pm_tenancy.monthly_total numeric not null",
        "pm_tenancy.payment_reference text not null",
        "pm_tenancy.rent numeric null",
        "pm_tenancy.rent_day integer not null",
        "pm_tenancy.start_date date not null",
        "pm_tenancy.state text not null",
        "pm_tenancy.tenancy_id uuid not null",
        "pm_tenancy.unit_id uuid not null",
        "pm_tenancy.workspace_id uuid not null",
        "pm_tenancy_party.contact_id uuid not null",
        "pm_tenancy_party.role text not null",
        "pm_tenancy_party.tenancy_id uuid not null",
        "pm_tenancy_party.workspace_id uuid not null",
        "pm_unit.base_rent numeric not null",
        "pm_unit.listing_ref text null",
        "pm_unit.market_state text not null default 'INVENTORY'::text",
        "pm_unit.name text not null",
        "pm_unit.property_id uuid not null",
        "pm_unit.unit_id uuid not null",
        "pm_unit.workspace_id uuid not null");

    /**
     * No foreign keys, which is the whole list and not an omission from it — see the class javadoc.
     */
    private static final List<String> EXPECTED_CONSTRAINTS = List.of(
        "pm_inspection pm_inspection_pkey PRIMARY KEY (inspection_id)",
        "pm_process_due pm_process_due_pkey PRIMARY KEY (kind, subject_id)",
        "pm_property pm_property_pkey PRIMARY KEY (property_id)",
        "pm_repair pm_repair_pkey PRIMARY KEY (repair_id)",
        "pm_tenancy pm_tenancy_pkey PRIMARY KEY (tenancy_id)",
        "pm_tenancy_party pm_tenancy_party_pkey PRIMARY KEY (tenancy_id, contact_id, role)",
        "pm_unit pm_unit_pkey PRIMARY KEY (unit_id)");

    private static final List<String> EXPECTED_INDEXES = List.of(
        "pm_inspection pm_inspection_due_idx CREATE INDEX pm_inspection_due_idx ON pm_inspection USING btree (property_id, next_due_on)",
        "pm_inspection pm_inspection_pkey CREATE UNIQUE INDEX pm_inspection_pkey ON pm_inspection USING btree (inspection_id)",
        "pm_process_due pm_process_due_pending_idx CREATE INDEX pm_process_due_pending_idx ON pm_process_due USING btree (kind, due_on) WHERE (fired_at IS NULL)",
        "pm_process_due pm_process_due_pkey CREATE UNIQUE INDEX pm_process_due_pkey ON pm_process_due USING btree (kind, subject_id)",
        "pm_property pm_property_pkey CREATE UNIQUE INDEX pm_property_pkey ON pm_property USING btree (property_id)",
        "pm_property pm_property_workspace_idx CREATE INDEX pm_property_workspace_idx ON pm_property USING btree (workspace_id)",
        "pm_repair pm_repair_open_idx CREATE INDEX pm_repair_open_idx ON pm_repair USING btree (asset_id) WHERE (completed_on IS NULL)",
        "pm_repair pm_repair_pkey CREATE UNIQUE INDEX pm_repair_pkey ON pm_repair USING btree (repair_id)",
        "pm_tenancy pm_tenancy_pkey CREATE UNIQUE INDEX pm_tenancy_pkey ON pm_tenancy USING btree (tenancy_id)",
        "pm_tenancy pm_tenancy_unit_idx CREATE INDEX pm_tenancy_unit_idx ON pm_tenancy USING btree (unit_id)",
        "pm_tenancy pm_tenancy_workspace_idx CREATE INDEX pm_tenancy_workspace_idx ON pm_tenancy USING btree (workspace_id)",
        "pm_tenancy_party pm_tenancy_party_by_tenancy CREATE INDEX pm_tenancy_party_by_tenancy ON pm_tenancy_party USING btree (workspace_id, tenancy_id)",
        "pm_tenancy_party pm_tenancy_party_pkey CREATE UNIQUE INDEX pm_tenancy_party_pkey ON pm_tenancy_party USING btree (tenancy_id, contact_id, role)",
        "pm_unit pm_unit_pkey CREATE UNIQUE INDEX pm_unit_pkey ON pm_unit USING btree (unit_id)",
        "pm_unit pm_unit_workspace_idx CREATE INDEX pm_unit_workspace_idx ON pm_unit USING btree (workspace_id)");
}
