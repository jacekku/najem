package pl.najem.acc.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.najem.acc.domain.ArrearsStanding;

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
 * <p>What the colour <em>is</em> lives in {@link ArrearsStanding}. This class reads what is open,
 * asks for the standing, and writes it down — the statute is a rule about obligations, not about
 * this module's plumbing, and keeping the two apart is what lets the rule be tested in
 * milliseconds.
 */
@Service
@Transactional
public class ArrearsBoardService {

    private final InvoiceRepository invoices;
    private final ArrearsStandingProjection standings;
    private final Clock clock;

    public ArrearsBoardService(InvoiceRepository invoices, ArrearsStandingProjection standings,
                               Clock clock) {
        this.invoices = invoices;
        this.standings = standings;
        this.clock = clock;
    }

    public void refresh(UUID workspaceId, UUID tenancyId) {
        var standing = ArrearsStanding.of(invoices.openInvoices(workspaceId, tenancyId), LocalDate.now(clock));
        standings.save(workspaceId, tenancyId, standing);
    }
}
