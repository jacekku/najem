package pl.najem.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import pl.najem.acc.adapter.bank.FakeBankAdapter;
import pl.najem.acc.application.BankStatementPort;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A production deployment must not obtain a bank by omission.
 *
 * <p>{@code FakeBankAdapter} was an unconditional {@code @Component} and the only
 * {@code BankStatementPort}, with a packaged {@code base-url} of {@code localhost:8081}. A real
 * deployment therefore started cleanly, reconciled nothing, and reported no error — every fetch
 * returned an empty statement because there was no fake bank to answer. Rule 7: where the value is
 * genuinely absent the application refuses to start.
 */
class BankPortWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(BankConfig.class)
        .withPropertyValues(
            "najem.bank.base-url=http://localhost:8081",
            "najem.bank.iban=PL61109010140000071219812874");

    @Test
    void withoutTheFakeBankFlagThereIsNoBankAtAll() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BankStatementPort.class));
    }

    @Test
    void theFlagMustBeSetDeliberately() {
        runner.withPropertyValues("najem.bank.fake.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(BankStatementPort.class));
    }

    @Test
    void withTheFlagTheFakeBankIsWired() {
        runner.withPropertyValues("najem.bank.fake.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(BankStatementPort.class));
    }

    /**
     * The ruling is "no flag ⇒ no port ⇒ the application refuses to start", and the middle step is
     * the one that decays: the first assertion above proves only that <em>this</em> adapter is
     * gated. A second implementation registered unconditionally would restore the silent start
     * without failing any test above, so the absence of one is asserted rather than assumed.
     *
     * <p>Adding a real bank adapter is expected to fail here. That is the moment to gate it too —
     * on its own property, mutually exclusive with the fake — not to relax this scan.
     */
    @Test
    void theFakeIsTheOnlyImplementationOfThePort() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BankStatementPort.class));
        var found = new ArrayList<Class<?>>();
        for (var candidate : scanner.findCandidateComponents("pl.najem")) {
            found.add(Class.forName(candidate.getBeanClassName()));
        }
        assertThat(found)
            .as("a new BankStatementPort must be gated on its own property, not left unconditional")
            .containsExactly(FakeBankAdapter.class);
    }

    /** Guards the scan above against a package rename silently emptying it. */
    @Test
    void theScanReachesTheProductionSources() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BankStatementPort.class));
        List<?> found = new ArrayList<>(scanner.findCandidateComponents("pl.najem"));
        assertThat(found).isNotEmpty();
    }
}
