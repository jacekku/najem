package pl.najem.pm.domain;

import java.math.BigDecimal;

/**
 * The tenant is quoted ONE number (total, mandatory). The optional breakdown serves
 * owner-income vs pass-through and ryczałt reporting (v1.1 amendment).
 */
public record MonthlyAmount(BigDecimal total, Breakdown breakdown) {

    /**
     * Whether the CONTRACT declares a split — not whether the numbers happen to be non-zero.
     * "No contractual split" and "split with a zero adminFee" are legally different: the first
     * triggers the collapse rule (the whole amount is czynsz, fully taxable and fully
     * valorizable). The integration contract carries this as an explicit boolean rather than
     * letting the ACL infer it from nulls.
     */
    public boolean componentSplitInContract() {
        return breakdown != null;
    }

    public record Breakdown(BigDecimal rent, BigDecimal adminFee, BigDecimal mediaAdvance) {

        public BigDecimal sum() {
            return rent.add(adminFee).add(mediaAdvance);
        }
    }
}
