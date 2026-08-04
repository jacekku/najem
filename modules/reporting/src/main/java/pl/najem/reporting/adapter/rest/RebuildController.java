package pl.najem.reporting.adapter.rest;

import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.reporting.application.ProjectionRunner;

/**
 * Discards a projection and replays it from the beginning of history — the operator action that
 * makes a read model safe to change shape.
 * <p>
 * <b>It is a separate, conditionally-registered controller rather than a method on
 * {@link ReportingController}, because that is what makes the gate real.</b> With no
 * {@link RebuildEnabled} property there is no bean, so there is no route and the path 404s — the
 * endpoint does not exist rather than existing and declining. There is no default: absent means
 * off.
 * <p>
 * It used to live on the read controller carrying a javadoc that said it "must be behind an admin
 * role the moment an issuer is configured". @najem-reviewer was right that this is the one thing
 * rule 7 forbids (najem-build seq 248): rule 7(2) is <em>where access is in dispute, access is
 * denied</em>, and <b>flagging rather than gating is how you grant it</b>. The comment described a
 * trigger with no mechanism behind it — nothing would have fired when @najem-frontend configured
 * an issuer, because a comment cannot observe that.
 * <p>
 * Replaying every projection is a denial of service anyone could trigger, so the deferral was
 * never affordable. This needs no role and duplicates no decision UserManagement owns: it is the
 * same shape as FakeBankAdapter's gate (seq 229, approved seq 239) — a capability that is absent
 * unless a deployment names it.
 */
@RestController
@RequestMapping("/api/reporting")
@Conditional(RebuildEnabled.class)
public class RebuildController {

    private final ProjectionRunner runner;

    public RebuildController(ProjectionRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/projections/{name}/rebuild")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rebuild(@PathVariable String name) {
        runner.rebuild(name);
    }
}
