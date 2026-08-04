package pl.najem.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clock the whole application dates its work by. These run without a context deliberately:
 * every other test in the tree supplies its own Clock, so the suite stays green whichever zone
 * this bean names -- which is exactly why the bean needs a test that fails when it is wrong.
 */
class PlatformClockTest {

    private final TimeZone original = TimeZone.getDefault();

    /**
     * Every test here runs under a JVM default of UTC, not just the one that asserts the zone.
     * The developer machines this was written on are already in Warsaw, so a test that reads the
     * bean's zone passes against systemDefaultZone() here and fails in the container -- it would
     * assert the machine rather than the bean. Making the hostile environment the default for the
     * class is what stops the next test in it being written that way.
     */
    @BeforeEach
    void runAsAContainerStartedWithoutATimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTheJvmDefault() {
        TimeZone.setDefault(original);
    }

    // systemDefaultZone() inherits whatever the deployment happened to set, and this application
    // sets nothing anywhere in its configuration. Naming the zone is what makes the environment
    // irrelevant.
    @Test
    void namesItsZoneRatherThanInheritingTheContainersTimezone() {
        assertThat(new PlatformConfig().clock().getZone()).isEqualTo(ZoneId.of("Europe/Warsaw"));
    }

    // What the inherited zone actually costs. At 00:30 in Warsaw on a summer night a UTC clock
    // still reads the previous day, so a rent charge due the 1st is raised on the 31st and lands
    // in the wrong month -- on some nights and not others, because the offset changes with DST.
    @Test
    void aWarsawMidnightIsAlreadyTheNewDayHereAndNotYetInUtc() {
        Instant justAfterWarsawMidnight = Instant.parse("2026-06-30T22:30:00Z");

        LocalDate here = LocalDate.ofInstant(justAfterWarsawMidnight, PlatformConfig.WARSAW);
        LocalDate inUtc = LocalDate.ofInstant(justAfterWarsawMidnight, ZoneId.of("UTC"));

        assertThat(here).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(inUtc).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    // Warsaw is one hour ahead in winter and two in summer. A fixed offset would be right for
    // half the year, which is worse than being wrong all of it: the failure would arrive months
    // after the change that caused it.
    @Test
    void tracksTheOffsetAcrossDaylightSaving() {
        Clock clock = new PlatformConfig().clock();

        assertThat(clock.getZone().getRules().getOffset(Instant.parse("2026-01-15T12:00:00Z")).getTotalSeconds())
            .isEqualTo(3600);
        assertThat(clock.getZone().getRules().getOffset(Instant.parse("2026-07-15T12:00:00Z")).getTotalSeconds())
            .isEqualTo(7200);
    }
}
