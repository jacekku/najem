package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Every component in the design system, on one page.
 *
 * <p>Two things this buys. The system is visible in one place, so drift is something you see
 * rather than something you discover on screen 20. And the states the handoff drew as buildable
 * but never designed — the empty state, and a field's validation error — get designed here once
 * instead of being improvised per screen. Two states the handoff names but this page does not
 * build — loading, and the blocking overlap-check error — are listed as undesigned at the bottom
 * of the gallery itself, not invented here to fill the gap.
 *
 * <p>It takes no workspace because it holds no tenant data: every value on the page is a literal
 * in {@code design.html}. That is also why it is safe for it to be a real route rather than
 * something behind a profile.
 */
@Controller
public class DesignGalleryController {

    @GetMapping("/design")
    public String gallery() {
        return "design";
    }
}
