package pl.najem.app.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.najem.app.NajemApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The teeth of roadmap rule 7 (human ruling, najem-build seq 171): a convenience default may exist
 * only in tests and must be impossible to acquire by omission.
 *
 * <p>Before this, an application with no {@code issuer-uri} silently permitted every request on
 * every module's endpoints. An unset environment variable was indistinguishable from a correct
 * deployment until the day somebody noticed the data was public.
 *
 * <p>Boots the real application rather than a slice, because the thing under test is what happens
 * when the context is assembled — a sliced test could not observe a refusal to start.
 */
@Testcontainers
@Tag("integration")
class SecurityFailsClosedTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Test
    void refusesToStartWithNoIssuerAndNoExplicitOptIn() {
        assertThatThrownBy(() -> boot().run(baseArgs()))
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to start")
            // Naming both properties is the point: an operator must not have to read the source to
            // learn which two options they are choosing between.
            .hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri")
            .hasMessageContaining("najem.security.permit-all");
    }

    @Test
    void startsWhenPermitAllIsRequestedExplicitly() {
        try (var context = boot().run(withArg("--najem.security.permit-all=true"))) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    /**
     * The property must not be satisfiable by anything other than a deliberate "true" — a present
     * but empty or garbled value is uncertainty, and uncertainty resolves to denied.
     */
    @Test
    void aNonTrueOptInIsNotAnOptIn() {
        assertThatThrownBy(() -> boot().run(withArg("--najem.security.permit-all=")))
            .rootCause()
            .hasMessageContaining("Refusing to start");

        assertThatThrownBy(() -> boot().run(withArg("--najem.security.permit-all=yes-please")))
            .rootCause()
            .hasMessageContaining("Refusing to start");
    }

    private static SpringApplicationBuilder boot() {
        return new SpringApplicationBuilder(NajemApplication.class);
    }

    /**
     * Uses the {@code --arg} form deliberately: {@code SpringApplicationBuilder.properties(...)}
     * registers DEFAULT properties, which {@code application.yml} then overrides — an e2e configured
     * that way silently dials localhost:5432 instead of the container (najem-build seq 96).
     */
    private static String[] baseArgs() {
        return new String[] {
            "--server.port=0",
            "--spring.datasource.url=" + pg.getJdbcUrl(),
            "--spring.datasource.username=" + pg.getUsername(),
            "--spring.datasource.password=" + pg.getPassword(),
            // A test is a deployment like any other and names its own bank: without these there is
            // no BankStatementPort and the context refuses to start for a reason that has nothing
            // to do with the security posture under test here.
            "--najem.bank.fake.enabled=true",
            "--najem.bank.base-url=http://localhost:8081",
            "--najem.bank.iban=PL61109010140000071219812874"
        };
    }

    private static String[] withArg(String extra) {
        String[] base = baseArgs();
        String[] all = java.util.Arrays.copyOf(base, base.length + 1);
        all[base.length] = extra;
        return all;
    }
}
