package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.MatchTier;
import pl.najem.acc.domain.PaymentIngested;
import pl.najem.eventstore.EventStore;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class IngestionService {

    private final BankStatementPort bank;
    private final EventStore store;
    private final JdbcTemplate jdbc;
    private final MatchingPolicy policy;
    private final Clock clock;

    @Autowired
    public IngestionService(BankStatementPort bank, EventStore store, JdbcTemplate jdbc,
                            @Value("${acc.matching.tiers-enabled:false}") boolean tiersEnabled,
                            @Value("${acc.matching.auto-confirm:false}") boolean autoConfirm) {
        this(bank, store, jdbc, new MatchingPolicy(tiersEnabled, autoConfirm));
    }

    public IngestionService(BankStatementPort bank, EventStore store, JdbcTemplate jdbc,
                            MatchingPolicy policy) {
        this(bank, store, jdbc, policy, Clock.systemDefaultZone());
    }

    public IngestionService(BankStatementPort bank, EventStore store, JdbcTemplate jdbc,
                            MatchingPolicy policy, Clock clock) {
        this.bank = bank;
        this.store = store;
        this.jdbc = jdbc;
        this.policy = policy;
        this.clock = clock;
    }

    /** Ingestion with the launch policy: tier 1 only, no automatic allocation. */
    public IngestionService(BankStatementPort bank, EventStore store, JdbcTemplate jdbc) {
        this(bank, store, jdbc, MatchingPolicy.tierOneOnly());
    }

    public void fetchAndIngest(UUID workspaceId) {
        for (BankLine line : bank.fetchSince(LocalDate.now(clock).minusDays(30))) {
            ingest(workspaceId, line);
        }
    }

    public void ingest(UUID workspaceId, BankLine line) {
        Integer existing = jdbc.queryForObject(
            "select count(*) from acc_payment where workspace_id = ? and external_id = ?",
            Integer.class, workspaceId, line.externalId());
        if (existing != null && existing > 0) {
            return;
        }
        UUID paymentId = UUID.randomUUID();
        store.append(paymentId, "Payment", 0,
            List.of(new PaymentIngested(paymentId, line.externalId(), line.amount(),
                line.title(), line.bookingDate())), List.of());
        jdbc.update("""
            insert into acc_payment(payment_id, workspace_id, external_id, amount, title, booking_date,
                                    status, unallocated_amount, counterparty_name, counterparty_iban,
                                    bank_reference, value_date, direction, currency)
            values (?,?,?,?,?,?,'unmatched',?,?,?,?,?,?,?)
            """, paymentId, workspaceId, line.externalId(), line.amount(), line.title(), line.bookingDate(),
            line.amount(),
            line.counterpartyName(), line.counterpartyIban(), line.bankReference(), line.valueDate(),
            line.creditDebitIndicator(), line.currency());
        climbTheLadder(workspaceId, paymentId, line);
    }

    /**
     * The ladder, rung by rung, stopping at the first that answers. A line only reaches it if it is
     * money coming in, in the currency this ledger holds — an outgoing debit or a euro transfer is
     * recorded as the bank fact it is and left for a human, however exactly its reference and amount
     * line up.
     *
     * <p>Tiers 2-4 are built and off. The rung that answers is recorded with the suggestion so the
     * manager can see whether the ledger is certain or merely guessing.
     */
    private void climbTheLadder(UUID workspaceId, UUID paymentId, BankLine line) {
        if (!line.isCredit() || !line.isZloty()) {
            return;
        }
        if (suggest(workspaceId, paymentId, exactMatch(workspaceId, line), MatchTier.EXACT)) {
            return;
        }
        if (!policy.tiersEnabled()) {
            return;
        }
        if (suggest(workspaceId, paymentId, referenceMatch(workspaceId, line), MatchTier.REFERENCE)) {
            return;
        }
        suggest(workspaceId, paymentId, rememberedPayerMatch(workspaceId, line),
            MatchTier.REMEMBERED_PAYER);
        // Nothing left to try: tier 4 is the manual queue, which is the 'unmatched' the row already has.
    }

    /** Tier 1 — the reference and the amount both name an open charge. */
    private Optional<UUID> exactMatch(UUID workspaceId, BankLine line) {
        return first(jdbc.queryForList("""
            select charge_id from acc_charge
            where workspace_id = ? and payment_reference = ? and amount = ? and not allocated and active
            order by due_date limit 1
            """, UUID.class, workspaceId, line.title(), line.amount()));
    }

    /**
     * Tier 2 — the reference survives being typed carelessly. Case and separators are discarded on
     * both sides, and the charge's reference need only appear somewhere in the title, because banks
     * prepend their own words and payers paste more than they were asked to.
     *
     * <p>The amount is deliberately not constrained: a part payment is still that tenant's money.
     * A reference that normalises to nothing matches no charge rather than every charge.
     *
     * <p>The <em>longest</em> matching reference wins, not the oldest charge. A short reference is a
     * substring of a longer one, so a payer naming {@code NAJEM/M1/2027/09} also literally names
     * {@code NAJEM/M1}; oldest-first would suggest a small January charge against a full September
     * payment, and the tier badge would read as mild uncertainty rather than as the wrong month.
     */
    private Optional<UUID> referenceMatch(UUID workspaceId, BankLine line) {
        String title = normalise(line.title());
        if (title.isEmpty()) {
            return Optional.empty();
        }
        return first(jdbc.queryForList("""
            select charge_id from acc_charge
            where workspace_id = ? and not allocated and active
              and regexp_replace(upper(payment_reference), '[^A-Z0-9]', '', 'g') <> ''
              and position(regexp_replace(upper(payment_reference), '[^A-Z0-9]', '', 'g') in ?) > 0
            order by length(regexp_replace(upper(payment_reference), '[^A-Z0-9]', '', 'g')) desc,
                     due_date
            limit 1
            """, UUID.class, workspaceId, title));
    }

    /**
     * Tier 3 — no usable reference, but this account has paid for a tenancy before and a manager
     * confirmed it. Suggests that tenancy's oldest open charge.
     *
     * <p>A free-text MT940 {@code :86:} carries no counterparty at all, which is an ordinary bank
     * sending less: with nothing to look up there is nothing to suggest, and the null must not
     * become a key that matches every other line which also arrived without one.
     *
     * <p>An account that pays for more than one tenancy — a guarantor with two children's flats —
     * cannot identify one from the account alone, so this rung declines and the line goes to a
     * human. Guessing would be wrong half the time and would look like the ledger's own opinion.
     */
    private Optional<UUID> rememberedPayerMatch(UUID workspaceId, BankLine line) {
        if (line.counterpartyIban() == null || line.counterpartyIban().isBlank()) {
            return Optional.empty();
        }
        var tenancies = jdbc.queryForList("""
            select tenancy_id from acc_payer_account
            where workspace_id = ? and counterparty_iban = ?
            """, UUID.class, workspaceId, line.counterpartyIban());
        if (tenancies.size() != 1) {
            return Optional.empty();
        }
        return first(jdbc.queryForList("""
            select charge_id from acc_charge
            where workspace_id = ? and tenancy_id = ? and not allocated and active
            order by due_date limit 1
            """, UUID.class, workspaceId, tenancies.getFirst()));
    }

    /** Records the rung that answered. Nothing here allocates — the manager still confirms. */
    private boolean suggest(UUID workspaceId, UUID paymentId, Optional<UUID> chargeId, MatchTier tier) {
        if (chargeId.isEmpty()) {
            return false;
        }
        jdbc.update("insert into acc_suggestion(payment_id, workspace_id, charge_id, tier) values (?,?,?,?)",
            paymentId, workspaceId, chargeId.get(), tier.number());
        jdbc.update("update acc_payment set status = 'suggested' where payment_id = ?", paymentId);
        return true;
    }

    private static Optional<UUID> first(List<UUID> ids) {
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }
}
