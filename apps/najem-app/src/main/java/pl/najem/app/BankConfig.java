package pl.najem.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.najem.acc.adapter.bank.FakeBankAdapter;
import pl.najem.acc.application.BankStatementPort;

/**
 * Which bank this deployment talks to.
 *
 * <p>Separate from {@link PlatformConfig} because it is the one piece of wiring a deployment is
 * expected to vary, and because it can then be exercised on its own: a test that must prove "no
 * flag means no bank" has to instantiate the real production configuration, not a copy of it that
 * could drift into being right while this one is wrong.
 */
@Configuration
public class BankConfig {

    /**
     * The flag carries no default, and neither does the URL. A deployment that says nothing gets no
     * {@link BankStatementPort}, and the application refuses to start rather than reconciling
     * against a bank that isn't there.
     *
     * <p>There is no {@code najem.bank.iban} any more, and its absence is the point. It answered
     * "whose money is this?", and a packaged value answers that with one agency's account for every
     * workspace in the system: the port handed the same lines to whichever workspace asked, each
     * matched them against its own charges, and one transfer could read as paid in two sets of
     * books. The account is now registered per workspace, where the question belongs.
     */
    @Bean
    @ConditionalOnProperty(name = "najem.bank.fake.enabled", havingValue = "true")
    BankStatementPort fakeBank(@Value("${najem.bank.base-url}") String baseUrl) {
        return new FakeBankAdapter(baseUrl);
    }
}
