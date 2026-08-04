package pl.najem.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import pl.najem.acc.application.ArrearsBoardQuery;

/**
 * The arrears board: one colour per tenancy, decided by Accounting.
 *
 * <p>The colour is rendered exactly as given and never derived here. It encodes a legal
 * distinction — {@code brightRed} means a whole rent period has elapsed unpaid and the art. 11
 * termination counter is running — and a template that recomputed it from amounts and dates would
 * be a second implementation of a rule with consequences outside this application.
 *
 * <p>Takes the enum rather than the wire string, on Accounting's own argument: a colour they stop
 * emitting then breaks this compile instead of leaving a branch here that renders forever.
 */
@Controller
public class ReportScreenController {

    private final ArrearsBoardQuery board;

    public ReportScreenController(ArrearsBoardQuery board) {
        this.board = board;
    }

    @GetMapping("/report")
    public String report(WebWorkspace workspace, Model model) {
        model.addAttribute("rows", board.forWorkspace(workspace.workspaceId()));
        return "report";
    }
}
