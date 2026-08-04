package pl.najem.acc.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ArrearsColour;
import pl.najem.acc.domain.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The arrears board, derived from the charges rather than stored as an opinion.
 *
 * <p>Every path that settles or reopens a charge re-derives the colour, so there is no way to reach
 * a tenancy whose colour is stale. That is why this is one place: the board used to be written
 * by whoever happened to be finishing an operation, and it went green because a confirmation had
 * happened rather than because anything was paid.
 *
 * <p>Yellow exists so that red means something. A charge posted the day before it falls due is not
 * arrears, and colouring it red on the day it appears would put the whole portfolio into the alarm
 * state on the ninth of every month — a board that shouts every month is a board nobody reads.
 */
@Service
@Transactional
public class BoardService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BoardService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void refresh(UUID workspaceId, UUID tenancyId) {
        jdbc.update("""
            insert into acc_tenancy_status(tenancy_id, workspace_id, status) values (?,?,?)
            on conflict (tenancy_id) do update set status = excluded.status
            """, tenancyId, workspaceId, colourFor(workspaceId, tenancyId).wireName());
    }

    /**
     * Overdue periods are counted as whole unpaid <em>rent</em> periods, per art. 11 u.o.p.l. — the
     * statute counts periods, not money, and a tenant three periods behind by a little is in a
     * different legal position from one a single period behind by a lot.
     *
     * <p>Only rent counts toward the period tally. An unpaid deposit is a real arrear and shows as
     * red, but it is not a rental period in arrears and must not advance the termination counter.
     */
    ArrearsColour colourFor(UUID workspaceId, UUID tenancyId) {
        LocalDate today = LocalDate.now(clock);
        Integer openCharges = jdbc.queryForObject("""
            select count(*) from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
            """, Integer.class, workspaceId, tenancyId);
        if (openCharges == null || openCharges == 0) {
            return ArrearsColour.GREEN;
        }
        Integer overdue = jdbc.queryForObject("""
            select count(*) from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and amount > allocated_amount
              and due_date < ?
            """, Integer.class, workspaceId, tenancyId, today);
        if (overdue == null || overdue == 0) {
            return ArrearsColour.YELLOW;
        }
        // A "full period" is a whole payment period elapsed in arrears, not merely a period whose
        // charge is unpaid: art. 11 speaks of zwloka za pelne okresy platnosci. A charge one day
        // past due is arrears (red); a charge still unpaid a month later is a full period (bright
        // red), and three of those is where termination becomes available.
        Integer fullPeriodsInArrears = jdbc.queryForObject("""
            select count(distinct due_date) from acc_charge
            where workspace_id = ? and tenancy_id = ? and active and allocated_amount = 0
              and due_date < ? and component = ?
            """, Integer.class, workspaceId, tenancyId, today.minusMonths(1),
            Component.RENT.wireName());
        return fullPeriodsInArrears != null && fullPeriodsInArrears >= 1
            ? ArrearsColour.BRIGHT_RED
            : ArrearsColour.RED;
    }
}
