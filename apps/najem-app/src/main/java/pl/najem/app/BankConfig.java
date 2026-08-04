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
     * The flag carries no default, and neither does the URL or the IBAN. A deployment that says
     * nothing gets no {@link BankStatementPort}, and the application refuses to start rather than
     * reconciling against a bank that isn't there.
     *
     * <p>{@code iban} is not a setting. It answers "whose money is this?" — and a packaged value
     * answers it with one agency's account for every workspace in the system.
     */
    @Bean
    @ConditionalOnProperty(name = "najem.bank.fake.enabled", havingValue = "true")
    BankStatementPort fakeBank(@Value("${najem.bank.base-url}") String baseUrl,
                               @Value("${najem.bank.iban}") String iban) {
        return new FakeBankAdapter(baseUrl, iban);
    }
}
