package pl.najem.app.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Which theme this request renders in, and where a toggle should send the person back to.
 *
 * <p><b>Why the server decides.</b> The theme could be chosen in the browser from localStorage,
 * which would need no endpoint and no cookie. It would also paint the wrong theme first: the
 * document renders, then a script corrects it, and every navigation flashes. Avoiding that flash
 * client-side means a blocking inline script in {@code <head>} — the one thing that delays first
 * paint, in an application that otherwise loads a single deferred script. Reading a cookie here
 * costs nothing and the first paint is already correct.
 *
 * <p><b>Absent is a third value, not a default.</b> No cookie means "follow the operating system",
 * which the stylesheet expresses with a media query. Only an explicit choice stamps
 * {@code data-theme}, so somebody who has never touched the control keeps tracking their machine
 * when it switches at sunset — a stored "light" would silently stop doing that.
 */
@ControllerAdvice(basePackageClasses = ThemeAdvice.class)
public class ThemeAdvice {

    static final String COOKIE = "najem-theme";
    static final String LIGHT = "light";
    static final String DARK = "dark";

    /**
     * The stamped theme, or null to follow the operating system.
     *
     * <p>Anything that is not exactly {@code light} or {@code dark} is treated as absent rather
     * than as an error. This value is echoed into an attribute on {@code <html>}, so a cookie
     * somebody hand-edited is untrusted input on its way into the document — and there is no
     * useful way to fail here anyway. A person cannot be shown an error page because their theme
     * cookie is malformed.
     */
    @ModelAttribute("theme")
    public String theme(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return LIGHT.equals(value) || DARK.equals(value) ? value : null;
            }
        }
        return null;
    }

    /** What the toggle should ask for next: the opposite of what is rendering now. */
    @ModelAttribute("themeToOffer")
    public String themeToOffer(HttpServletRequest request) {
        return DARK.equals(theme(request)) ? LIGHT : DARK;
    }

    /**
     * Where the toggle returns to, so switching theme keeps you on the screen you were reading.
     *
     * <p>Taken from the request being rendered rather than from the {@code Referer} header: the
     * header is supplied by the browser and can be absent, stale, or another site entirely, and
     * this value is about to become a redirect target. {@link ThemeController} validates it again
     * on the way back in — this is where it comes from, not where it is trusted.
     */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return query == null ? path : path + "?" + query;
    }
}
