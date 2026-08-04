package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.WorkspaceAccountService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/acc")
public class WorkspaceAccountController {

    private final WorkspaceAccountService accounts;

    public WorkspaceAccountController(WorkspaceAccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * Names the account this workspace's bank statements are fetched from.
     *
     * <p>The header is required, as on every write. It matters more here than elsewhere: this is the
     * write that decides whose money the module will read, so a call that named no workspace would
     * be pointing an agency's statement at books nobody identified.
     */
    @PutMapping("/workspace-account")
    public void register(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                         @RequestBody Map<String, String> body) {
        accounts.register(workspaceId, body.get("iban"));
    }
}
