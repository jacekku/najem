package pl.najem.pm.adapter.rest;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.pm.application.EndOfTenancyProcess;
import pl.najem.pm.application.RentChangeProcess;
import pl.najem.pm.application.TenancyStartProcess;

import java.time.LocalDate;
import java.util.Map;

/**
 * Drives PM's process managers at a chosen date so an end-to-end test can watch a tenancy start
 * and end without waiting for the wall clock.
 *
 * <p>The plan proposed guarding this with {@code @Profile("!prod")}. That is reachable by
 * omission — an unset {@code SPRING_PROFILES_ACTIVE} exposes it — which is exactly the shape
 * rule 7 forbids. It is opt-in instead: absent the property, the bean does not exist, and no
 * amount of forgetting to set something can bring it back.
 */
@RestController
@RequestMapping("/api/pm/processes")
@Conditional(TestEndpointsEnabled.class)
public class ProcessRunnerController {

    private final TenancyStartProcess start;
    private final RentChangeProcess rentChanges;
    private final EndOfTenancyProcess endings;

    public ProcessRunnerController(TenancyStartProcess start, RentChangeProcess rentChanges,
                                   EndOfTenancyProcess endings) {
        this.start = start;
        this.rentChanges = rentChanges;
        this.endings = endings;
    }

    /** Returns what each sweep did, including what it could not do — see SweepResult. */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam LocalDate on) {
        return Map.of(
            "started", start.runDue(on),
            "rentChanges", rentChanges.runDue(on),
            "endingSoon", endings.runDue(on));
    }
}
