package pl.najem.pm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class PmConfig {

    /** Injected rather than called statically so the date-driven processes are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
