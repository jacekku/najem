package pl.najem.um.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The config-seeded platform-operator account (coordinator ruling, najem-build seq 56, hole (ii)).
 *
 * <p>Invite-only means every user arrives through an invitation issued by a workspace ADMIN — which
 * leaves a fresh deployment with nobody to issue the first one. This seeds exactly one account from
 * a configured Keycloak subject so a real human can create the first workspace and become its ADMIN.
 *
 * <p>It exists ONLY when {@code najem.bootstrap.operator-subject} is set. There is deliberately no
 * default: an implicit operator would be an unauthenticated way into every deployment.
 */
@Component
@ConditionalOnProperty("najem.bootstrap.operator-subject")
public class PlatformOperator {

    private final UserService users;
    private final UUID subject;
    private final Clock clock;

    public PlatformOperator(UserService users, @Value("${najem.bootstrap.operator-subject}") String subject,
                            Clock clock) {
        this.users = users;
        this.subject = UUID.fromString(subject);
        this.clock = clock;
    }

    /** Registers the operator on first use; idempotent, so restarts do not create duplicates. */
    public UUID userId() {
        return users.findBySubject(subject).orElseGet(() -> users.register(subject, LocalDate.now(clock)));
    }

    public UUID subject() {
        return subject;
    }
}
