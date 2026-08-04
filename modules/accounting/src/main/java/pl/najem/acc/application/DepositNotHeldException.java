package pl.najem.acc.application;

import java.util.UUID;

/**
 * There is no deposit to give back: none was ever charged for this tenancy, it belongs to another
 * workspace, or it was charged and never actually paid.
 *
 * <p>The unpaid case is the one worth naming. A deposit charge that nobody settled is not money the
 * landlord is holding, so there is nothing to valorize and nothing to return — computing a figure
 * for it would invent funds and then pay them out.
 */
public class DepositNotHeldException extends RuntimeException {

    public DepositNotHeldException(UUID tenancyId, String because) {
        super("no deposit is held for tenancy " + tenancyId + ": " + because);
    }
}
