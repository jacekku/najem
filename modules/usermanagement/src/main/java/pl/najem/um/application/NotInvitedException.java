package pl.najem.um.application;

import org.springframework.security.access.AccessDeniedException;

/**
 * Signed in at the identity provider, but there is no NAJEM account for that subject.
 *
 * <p>NAJEM is invite-only (human ruling): an account exists because an ADMIN issued an invitation
 * and somebody accepted it. <b>Arriving with a perfectly valid token creates nothing.</b> Keycloak
 * may host other applications and other realm users; being one of them is not being a NAJEM user.
 *
 * <p>Extends {@link AccessDeniedException} rather than replacing it, so every existing handler still
 * treats it as the denial it is. It exists as its own type only so the screens can say what actually
 * happened — "you have not been invited" rather than "access denied", which is true but describes a
 * permissions problem to somebody who has an invitation problem.
 */
public class NotInvitedException extends AccessDeniedException {

    public NotInvitedException(String message) {
        super(message);
    }
}
