package pl.najem.acc.application;

import pl.najem.acc.domain.HeldDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link DepositRepository} kept in a map.
 *
 * <p>The unpaid figure is <strong>not</strong> stored here. The real query joins acc_charge and
 * reads {@code amount - allocated_amount} from the invoice, so whether the deposit is money in hand
 * is a fact about the charge. This fake asks the same {@link InvoiceRepository} the rest of the test
 * is using — keeping its own copy would let it say "paid" about an invoice nobody paid, which is
 * precisely the assertion {@code held} exists to make.
 */
public class InMemoryDepositRepository implements DepositRepository {

    public record Settlement(LocalDate returnedOn, BigDecimal rentAtReturn, BigDecimal valorized,
                             BigDecimal deducted, BigDecimal returned) {
    }

    public record Deduction(UUID depositId, UUID invoiceId, BigDecimal amount, LocalDate on) {
    }

    private record Stored(UUID workspaceId, UUID tenancyId, DepositToCharge deposit, boolean settled) {
    }

    private final Map<UUID, Stored> deposits = new LinkedHashMap<>();
    private final List<Deduction> deductions = new ArrayList<>();
    private final Map<UUID, Settlement> settlements = new LinkedHashMap<>();
    private final InMemoryInvoiceRepository invoices;

    public InMemoryDepositRepository(InMemoryInvoiceRepository invoices) {
        this.invoices = invoices;
    }

    public List<Deduction> deductions() {
        return List.copyOf(deductions);
    }

    public Settlement settlementOf(UUID depositId) {
        return settlements.get(depositId);
    }

    @Override
    public void charge(UUID workspaceId, UUID tenancyId, DepositToCharge deposit) {
        deposits.put(deposit.depositId(), new Stored(workspaceId, tenancyId, deposit, false));
    }

    @Override
    public Optional<HeldDeposit> find(UUID workspaceId, UUID tenancyId) {
        return deposits.values().stream()
            .filter(stored -> stored.workspaceId().equals(workspaceId))
            .filter(stored -> stored.tenancyId().equals(tenancyId))
            .findFirst()
            .map(stored -> new HeldDeposit(stored.deposit().depositId(),
                stored.deposit().multiplier(), stored.deposit().amount(), stored.settled(),
                unpaidOn(stored.deposit().invoiceId())));
    }

    @Override
    public void deduct(UUID workspaceId, UUID depositId, UUID invoiceId, BigDecimal amount,
                       LocalDate deductedOn) {
        deductions.add(new Deduction(depositId, invoiceId, amount, deductedOn));
    }

    @Override
    public void settle(UUID workspaceId, UUID depositId, LocalDate returnedOn,
                       BigDecimal rentAtReturn, BigDecimal valorized, BigDecimal deducted,
                       BigDecimal returned) {
        var stored = deposits.get(depositId);
        deposits.put(depositId, new Stored(stored.workspaceId(), stored.tenancyId(),
            stored.deposit(), true));
        settlements.put(depositId,
            new Settlement(returnedOn, rentAtReturn, valorized, deducted, returned));
    }

    /** The join, done the only honest way available here: by asking the invoice. */
    private BigDecimal unpaidOn(UUID invoiceId) {
        var invoice = invoices.find(invoiceId);
        return invoice.amount().subtract(invoice.allocatedAmount());
    }
}
