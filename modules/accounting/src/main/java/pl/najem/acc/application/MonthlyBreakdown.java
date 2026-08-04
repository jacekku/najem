package pl.najem.acc.application;

import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The monthly figure as the contract expresses it: one agreed total, plus the legal split when the
 * contract actually carries one.
 *
 * <p>{@code componentSplitInContract} is carried explicitly rather than inferred from null
 * components, because "no contractual split" and "a split that happens to have no admin fee" are
 * legally different: only the first drags media and administration into the ryczałt base and into
 * the deposit valorization base.
 */
public record MonthlyBreakdown(BigDecimal monthlyTotal, boolean componentSplitInContract,
                               BigDecimal rent, BigDecimal adminFee, BigDecimal mediaAdvance) {

    public static MonthlyBreakdown unsplit(BigDecimal monthlyTotal) {
        return new MonthlyBreakdown(monthlyTotal, false, null, null, null);
    }

    public static MonthlyBreakdown split(BigDecimal monthlyTotal, BigDecimal rent,
                                         BigDecimal adminFee, BigDecimal mediaAdvance) {
        return new MonthlyBreakdown(monthlyTotal, true, rent, adminFee, mediaAdvance);
    }

    /**
     * The collapse rule (accounting-domain-model §2): without a contractual split the whole amount
     * is a single {@code rent} line — fully taxable AND fully valorizable.
     */
    public List<ChargeLine> chargeLines() {
        if (!componentSplitInContract || splitIsEmpty()) {
            return List.of(new ChargeLine(Component.RENT, monthlyTotal));
        }
        var lines = new ArrayList<ChargeLine>();
        addIfCharged(lines, Component.RENT, rent);
        addIfCharged(lines, Component.ADMIN_FEE, adminFee);
        addIfCharged(lines, Component.MEDIA_ADVANCE, mediaAdvance);
        return List.copyOf(lines);
    }

    public List<String> warnings() {
        var warnings = new ArrayList<String>();
        if (!componentSplitInContract) {
            warnings.add("no contractual split: the entire amount enters the ryczałt base "
                + "and the deposit valorization base");
        } else if (splitIsEmpty()) {
            warnings.add("no contractual split: a split was declared but no components were given, "
                + "so the whole amount was charged as rent");
        } else {
            var sum = chargeLines().stream()
                .map(ChargeLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(monthlyTotal) != 0) {
                warnings.add("the contractual breakdown (" + sum + ") does not sum to the agreed "
                    + "monthly total (" + monthlyTotal + "); the breakdown was charged");
            }
        }
        return List.copyOf(warnings);
    }

    private boolean splitIsEmpty() {
        return isNothing(rent) && isNothing(adminFee) && isNothing(mediaAdvance);
    }

    private static void addIfCharged(List<ChargeLine> lines, Component component, BigDecimal amount) {
        if (!isNothing(amount)) {
            lines.add(new ChargeLine(component, amount));
        }
    }

    private static boolean isNothing(BigDecimal amount) {
        return amount == null || amount.signum() == 0;
    }
}
