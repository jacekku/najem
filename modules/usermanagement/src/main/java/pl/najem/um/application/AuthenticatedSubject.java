package pl.najem.um.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Which Keycloak subject is making this request, if any.
 *
 * <p>A driven port, and the reason one exists at all: {@code WorkspaceCaller} used to import
 * {@code pl.najem.um.adapter.security.CurrentUser} to answer this — an application class naming an
 * adapter, the one arrow the ports exist to prevent (rule 4). The question it was asking is
 * legitimate; only the direction was wrong.
 *
 * <p>Deliberately expressed in NAJEM's terms and not Spring Security's. {@code Jwt} and
 * {@code OidcUser} are how a subject arrives, which is an adapter's business — a bearer token for a
 * machine client, an authorization-code login for a person. What the application needs to know is
 * narrower than either: a subject, or nobody.
 *
 * <p><b>Empty means nobody is authenticated, which is not the same as being refused.</b> Under
 * permit-all — local runs and the walking skeleton — there is genuinely no caller, and the
 * configured platform operator is the correct answer there. A port that threw instead of reporting
 * absence would collapse those two states and take that decision away from
 * {@link ActingCaller}, which is the class that owns it.
 */
public interface AuthenticatedSubject {

    Optional<UUID> current();
}
