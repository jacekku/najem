package pl.najem.acc.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.najem.acc.TestWorkspace;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The account register's rules, without a database.
 *
 * <p>{@code WorkspaceBankAccountTest} is the authority and asks the question that matters most —
 * that two workspaces cannot share an account — because that one is a unique index and only Postgres
 * can answer it. What is here is what this layer decides: what counts as an iban, what happens when
 * nobody registered one, and that re-registering replaces rather than accumulates.
 */
class WorkspaceAccountInMemoryTest {

    private static final UUID WS = TestWorkspace.ID;
    private static final LocalDate TODAY = LocalDate.of(2027, 3, 15);
    private static final String IBAN = "PL61109010140000071219812874";

    private InMemoryWorkspaceAccountRepository accounts;
    private WorkspaceAccountService service;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryWorkspaceAccountRepository();
        service = new WorkspaceAccountService(accounts,
            Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
    }

    @Test
    void aRegisteredAccountIsWhatIngestionWillFetchFrom() {
        service.register(WS, IBAN);

        assertThat(service.accountOf(WS)).isEqualTo(IBAN);
        assertThat(accounts.registeredOn(WS)).isEqualTo(TODAY);
    }

    /**
     * Rule 7 in the one place where getting it wrong misattributes money rather than leaking a read:
     * a workspace nobody gave an account to must not fall back to anybody else's.
     */
    @Test
    void aWorkspaceWithNoAccountIsRefusedRatherThanGivenADefault() {
        assertThatThrownBy(() -> service.accountOf(UUID.randomUUID()))
            .isInstanceOf(NoBankAccountRegisteredException.class);
    }

    /** Agencies change banks. The new account replaces the old one rather than joining it. */
    @Test
    void reRegisteringReplacesTheAccount() {
        service.register(WS, IBAN);
        service.register(WS, "PL27114020040000300201355387");

        assertThat(service.accountOf(WS)).isEqualTo("PL27114020040000300201355387");
    }

    @Test
    void anAccountRegistrationNeedsAnIban() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.register(WS, nothing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs an iban");
        }
        assertThatThrownBy(() -> service.accountOf(WS))
            .isInstanceOf(NoBankAccountRegisteredException.class);
    }

    /**
     * Stored stripped, because an iban that differs from another only by a copied-in space is the
     * same account — and the index that keeps two workspaces off one account compares the stored
     * text.
     */
    @Test
    void surroundingWhitespaceIsNotPartOfTheAccount() {
        service.register(WS, "  " + IBAN + "\n");

        assertThat(service.accountOf(WS)).isEqualTo(IBAN);
    }
}
