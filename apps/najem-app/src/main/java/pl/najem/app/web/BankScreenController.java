package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import pl.najem.acc.application.ReconciliationService;
import pl.najem.acc.application.SuggestionQuery;
import pl.najem.acc.application.SuspenseService;
import pl.najem.acc.application.WarningService;

import java.util.UUID;

/**
 * The bank screen: what the ladder proposes, what it could not place, and what it wants a person
 * to look at.
 *
 * <p>Three lists, deliberately not merged. A suggestion is the ladder proposing a match; a
 * suspense line is money that came to rest nowhere and needs a decision; a warning is a compliance
 * flag that never blocked anything. Collapsing them into one "inbox" would hide which of the three
 * a manager is actually being asked for.
 *
 * <p>Renders accounting's answers and computes none of them. The tier is theirs; whether a payment
 * is a part payment is theirs (getting it wrong is a money error, so it is computed once, in the
 * module that owns the rule, rather than inferred from two amounts in a template).
 */
@Controller
public class BankScreenController {

    private final SuggestionQuery suggestions;
    private final SuspenseService suspense;
    private final WarningService warnings;
    private final ReconciliationService reconciliation;

    public BankScreenController(SuggestionQuery suggestions, SuspenseService suspense,
                                WarningService warnings, ReconciliationService reconciliation) {
        this.suggestions = suggestions;
        this.suspense = suspense;
        this.warnings = warnings;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/bank")
    public String bank(WebWorkspace workspace, Model model) {
        UUID workspaceId = workspace.workspaceId();
        model.addAttribute("suggestions", suggestions.forWorkspace(workspaceId));
        model.addAttribute("suspense", suspense.waiting(workspaceId));
        model.addAttribute("warnings", warnings.unseen(workspaceId));
        return "bank";
    }

    /**
     * Confirming is the manager's decision, not the ladder's. Tiers 2 and above are proposals with
     * the evidence shown next to them precisely so this click means "I have looked".
     */
    @PostMapping("/bank/payments/{paymentId}/confirm")
    public String confirm(@PathVariable UUID paymentId, WebWorkspace workspace) {
        reconciliation.confirm(workspace.workspaceId(), paymentId);
        return "redirect:/bank";
    }

    @PostMapping("/bank/warnings/{warningId}/seen")
    public String seen(@PathVariable UUID warningId, WebWorkspace workspace) {
        warnings.markSeen(workspace.workspaceId(), warningId);
        return "redirect:/bank";
    }
}
