package pl.najem.app;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The bank and security properties are supplied here rather than packaged: without them there is
 * no {@link pl.najem.acc.application.BankStatementPort} and no security posture, and this context
 * legitimately fails to start. A test is a deployment like any other and names its own bank and
 * its own security.
 */
@SpringBootTest(properties = {
    "najem.bank.fake.enabled=true",
    "najem.bank.base-url=http://localhost:8081",
    "najem.bank.iban=PL61109010140000071219812874",
    "najem.security.permit-all=true"})
@Tag("integration")
class NajemApplicationTest extends SharedDatabase {

    @Test
    void contextLoads() {
    }
}
