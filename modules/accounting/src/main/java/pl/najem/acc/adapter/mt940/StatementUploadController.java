package pl.najem.acc.adapter.mt940;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.mt940.Mt940FormatException;

import java.util.Map;
import java.util.UUID;

/** Uploading a bank statement the manager downloaded from their bank. */
@RestController
@RequestMapping("/api/acc")
public class StatementUploadController {

    private final Mt940Import imports;

    public StatementUploadController(Mt940Import imports) {
        this.imports = imports;
    }

    /**
     * The workspace header is <strong>required</strong> here, as it now is on every mapping in this
     * module — the read endpoints that used to fall back to a dev workspace no longer do.
     *
     * <p>It has always mattered most on this one. A write with no header puts a real bank statement
     * into books nobody named, returns 201, and says nothing — and per-workspace uniqueness on
     * {@code external_id} means the misplaced copy never collides with the correct import, so it
     * persists after the mistake is found. A missing header is a 400 rather than a guess.
     *
     * <p>The header is a <strong>stand-in until the workspace is taken from the verified token and checked against the
     * caller's memberships</strong>. Requiring it closes omission, not
     * impersonation — a caller may still name a workspace they are not a member of.
     */
    @PostMapping(value = "/statements", consumes = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(
        @RequestBody String statement,
        @RequestHeader("X-Workspace-Id") UUID workspaceId) {
        return Map.of("linesRead", imports.importStatement(workspaceId, statement));
    }

    /** An unreadable statement is the uploader's problem to fix, not a server fault. */
    @ExceptionHandler(Mt940FormatException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> unreadable(Mt940FormatException e) {
        return Map.of("error", e.getMessage());
    }
}
