package pl.najem.acc.application;

import java.time.LocalDate;
import java.util.List;

/**
 * A source of bank statement lines for one account.
 *
 * <p>The account is a parameter rather than a property of the implementation, and that is the whole
 * design. A port bound to a single configured account hands the same lines to whichever workspace
 * asks, and since each workspace matches them against its own charges, one transfer can be credited
 * in two sets of books by the ordinary path — no attacker and no missing predicate required. Naming
 * the account per call removes the possibility instead of documenting it.
 */
public interface BankStatementPort {

    List<BankLine> fetchSince(LocalDate since, String iban);
}
