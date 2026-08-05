package pl.najem.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The composition root, scanning the packages it actually composes and no others.
 *
 * <p><b>Why this is a list rather than {@code "pl.najem"}.</b> A scan of the whole namespace picks
 * up anything that ever lands under it on the classpath — including another application. {@code
 * :e2e} puts {@code :apps:fakebank} on the same test classpath as this app, so {@code
 * pl.najem.fakebank.BankUiController} was scanned into <em>this</em> context, where {@code
 * HomeController} already maps {@code GET /}. Seven e2e classes failed with {@code Ambiguous
 * mapping}, six of which never mention FakeBank at all — a context that was never told about a bank
 * failing on a bank bean. Found by najem-fakebank, who was assigned the fix and demonstrated it
 * belonged here instead.
 *
 * <p>The latency is the point: this has been wrong since {@code :apps:fakebank} joined that
 * classpath, and stayed invisible until somebody added a second mapping wide enough to collide.
 * A namespace scan cannot fail on the day it becomes wrong.
 *
 * <p><b>The list is derived, not guessed.</b> These six are the packages holding Spring
 * stereotypes. {@code pl.najem.eventstore}, {@code pl.najem.mt940} and {@code pl.najem.contracts}
 * have none — the event store is wired by an explicit {@code @Bean} in {@link PlatformConfig}, and
 * the other two are a parser and a set of records. <b>Adding a module now means a missing bean at
 * startup, loud and immediate, instead of a namespace that silently absorbs whatever appears.</b>
 */
@SpringBootApplication(scanBasePackages = {
    "pl.najem.app",
    "pl.najem.acc",
    "pl.najem.pm",
    "pl.najem.um",
    "pl.najem.reporting",
    "pl.najem.contacts"})
@EnableScheduling
public class NajemApplication {

    public static void main(String[] args) {
        SpringApplication.run(NajemApplication.class, args);
    }
}
