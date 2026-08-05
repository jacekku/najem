package pl.najem.acc.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Coordinates the accounting module's use cases: what must happen together when money moves.
 *
 * <p>{@link AllocationService} decides where a payment comes to rest, and that is all it decides.
 * What the tenant's standing looks like afterwards is a consequence, not part of the rule, so the
 * two are sequenced here rather than one calling the other. Settling and reporting are separate
 * concerns that happen to share a moment.
 *
 * <p>This is deliberately a holding place. As the module's raw JdbcTemplate use is extracted, the
 * coordination that surfaces lands here until there is enough of it to see where the real
 * boundaries are, at which point this splits along them.
 */
@Service
@Transactional
public class AccountingService {

    private final AllocationService allocation;
    private final ArrearsBoardService board;

    @Autowired
    public AccountingService(AllocationService allocation, ArrearsBoardService board) {
        this.allocation = allocation;
        this.board = board;
    }

    /**
     * Brings a payment to rest against a tenancy's open invoices and leaves the arrears board
     * telling the truth about what is left.
     *
     * <p>The board is re-derived rather than assigned, and it is re-derived here for every route
     * money takes: confirming a suggestion, a manager allocating by hand, and moving a payment to
     * the right tenancy are the same event as far as the tenant's standing is concerned.
     *
     * @return what came to rest; the remainder stays on the payment as the tenant's credit
     */
    public BigDecimal allocate(UUID workspaceId, UUID paymentId, UUID tenancyId) {
        BigDecimal allocated = allocation.allocate(workspaceId, paymentId, tenancyId);
        board.refresh(workspaceId, tenancyId);
        return allocated;
    }
}
