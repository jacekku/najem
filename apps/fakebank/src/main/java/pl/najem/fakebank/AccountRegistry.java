package pl.najem.fakebank;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The accounts this bank has been told about.
 *
 * <p>Deliberately separate from {@link TransactionStore}: an account can exist with no transactions
 * (just opened) and transactions can exist for an account nobody registered (seeded straight in by
 * a scenario, which is how every account worked before this existed). Neither is an error, and
 * folding the two into one map would make one of them impossible.
 *
 * <p>In memory, like everything else here. Restart clears it.
 */
@Component
public class AccountRegistry {

    private final Map<String, BankAccount> accounts = new ConcurrentHashMap<>();

    /**
     * @throws IllegalArgumentException if the IBAN is already registered. Re-registering would
     *                                  silently replace an opening balance, and a balance that
     *                                  changes without anybody booking anything is the one thing a
     *                                  bank must not do.
     */
    public BankAccount open(BankAccount account) {
        BankAccount existing = accounts.putIfAbsent(account.iban(), account);
        if (existing != null) {
            throw new IllegalArgumentException("Account " + account.iban() + " already exists");
        }
        return account;
    }

    public Optional<BankAccount> find(String iban) {
        return Optional.ofNullable(accounts.get(iban));
    }

    /** The opening balance to use for a statement, or zero for an account nobody registered. */
    public java.math.BigDecimal openingBalanceFor(String iban, String currency) {
        return find(iban).map(account -> account.openingBalanceIn(currency))
            .orElse(java.math.BigDecimal.ZERO);
    }

    public java.util.List<String> ibans() {
        return accounts.keySet().stream().sorted().toList();
    }
}
