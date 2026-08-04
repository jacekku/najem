package pl.najem.um.adapter.rest;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.adapter.security.CurrentUser;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.application.WorkspaceCaller;
import pl.najem.um.application.WorkspaceService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/um")
public class WorkspaceController {

    public record CreateRequest(String name) {}

    private final WorkspaceService workspaces;
    private final WorkspaceAccess access;
    private final CurrentUser currentUser;
    private final WorkspaceCaller caller;

    public WorkspaceController(WorkspaceService workspaces, WorkspaceAccess access,
                               CurrentUser currentUser, WorkspaceCaller caller) {
        this.workspaces = workspaces;
        this.access = access;
        this.currentUser = currentUser;
        this.caller = caller;
    }

    /** The caller becomes the new workspace's first ADMIN — see WorkspaceService.create. */
    @PostMapping("/workspaces")
    public Map<String, UUID> create(@RequestBody CreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID creator = caller.resolveWithoutWorkspace(jwt);
        return Map.of("workspaceId", workspaces.create(request.name(), creator, LocalDate.now()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = caller.resolveWithoutWorkspace(jwt);
        UUID subject = jwt != null ? currentUser.subject(jwt) : subjectOf(userId);
        List<Map<String, Object>> workspaceList = access.forSubject(subject).stream()
            .map(m -> Map.<String, Object>of("workspaceId", m.workspaceId(), "role", m.role().name()))
            .toList();
        return Map.of("userId", userId, "workspaces", workspaceList);
    }

    private UUID subjectOf(UUID userId) {
        return access.subjectOf(userId).orElseThrow();
    }
}
