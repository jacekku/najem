package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the sidebar footer's avatar shows, and — the half that matters more — when it shows nothing.
 *
 * <p>The footer called {@code avatar(initials=null)} literally, so the circle was blank for
 * everybody on every screen. That is not the kind of defect a rendered-page check finds, because a
 * blank circle is also the correct rendering under permit-all: there is no session, so there is no
 * name to abbreviate. The bug and the intended behaviour looked identical, which is why the
 * distinction is pinned here rather than left to a screenshot.
 *
 * <p>No Spring and no database: {@code signedInInitials} reads the principal and nothing else, so
 * the resolver the constructor takes is never touched on any path below.
 */
class SidebarAvatarInitialsTest {

    private final ActiveAgencyAdvice advice = new ActiveAgencyAdvice(null);

    @Test
    void nobodySignedInMeansNoInitials_soTheCircleStaysBlank() {
        assertThat(advice.signedInInitials(null))
            .as("permit-all has no session; a placeholder here would invent a person")
            .isNull();
    }

    @Test
    void aPreferredUsernameGivesTwoInitials() {
        assertThat(advice.signedInInitials(signedIn("anna.kowalska"))).isEqualTo("AK");
        assertThat(advice.signedInInitials(signedIn("tomasz_lewandowski"))).isEqualTo("TL");
        assertThat(advice.signedInInitials(signedIn("ewa-kaminska"))).isEqualTo("EK");
        assertThat(advice.signedInInitials(signedIn("Jakub Szymanski"))).isEqualTo("JS");
    }

    @Test
    void aSingleNameGivesOneInitialRatherThanTwoLettersOfIt() {
        assertThat(advice.signedInInitials(signedIn("operator")))
            .as("\"OP\" would be two characters of one word dressed up as a first and last name")
            .isEqualTo("O");
    }

    @Test
    void onlyTheFirstTwoPartsCount() {
        assertThat(advice.signedInInitials(signedIn("anna.maria.kowalska"))).isEqualTo("AM");
    }

    /**
     * The case that must not produce letters. {@code signedInAs} falls back to the subject when
     * Keycloak sends no preferred username, and a subject is a UUID — so the fallback has to stop
     * here rather than render two characters of an identifier as though they were somebody's name.
     */
    @Test
    void aSubjectWithNoPreferredUsernameGivesNoInitials() {
        assertThat(advice.signedInInitials(signedIn(null)))
            .as("a UUID abbreviates to nothing meaningful; blank is the honest answer")
            .isNull();
    }

    /** A UUID that happens to start with a hex letter is still a UUID, not a name. */
    @Test
    void aSubjectStartingWithALetterIsStillNotAName() {
        String initials = advice.signedInInitials(signedInWithSubject(null,
            "abcdef01-2345-4000-8000-00000000000a"));
        assertThat(initials)
            .as("hex digits that happen to be letters must not become initials")
            .isNull();
    }

    private static OidcUser signedIn(String preferredUsername) {
        return signedInWithSubject(preferredUsername, "3f1d9c22-0000-4000-8000-00000000000a");
    }

    private static OidcUser signedInWithSubject(String preferredUsername, String subject) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        if (preferredUsername != null) {
            claims.put("preferred_username", preferredUsername);
        }
        OidcIdToken token = new OidcIdToken(
            "id-token", Instant.now(), Instant.now().plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(), token);
    }
}
