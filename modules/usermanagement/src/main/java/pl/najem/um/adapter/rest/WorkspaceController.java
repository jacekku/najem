package pl.najem.um.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.um.application.WorkspaceAccess;
import pl.najem.um.application.WorkspaceService;

import java.time.Clock;
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
    private final Clock clock;

    public WorkspaceController(WorkspaceService workspaces, WorkspaceAccess access, Clock clock) {
        this.workspaces = workspaces;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Deliberately carries no {@code @PreAuthorize} on the service, because there is no workspace to
     * hold a role in yet — any authenticated person may found an agency, and the caller becomes its
     * first ADMIN (see {@code WorkspaceService.create}). The authorization that matters here is
     * simply being somebody, which {@link ActingUser} enforces by refusing to resolve a caller who
     * has no NAJEM account.
     */
    @PostMapping("/workspaces")
    public Map<String, UUID> create(@RequestBody CreateRequest request, @ActingUser UUID creator) {
        return Map.of("workspaceId", workspaces.create(request.name(), creator, LocalDate.now(clock)));
    }

    /** Your own memberships. Scoped to the caller by construction — there is no id to pass. */
    @GetMapping("/me")
    public Map<String, Object> me(@ActingUser UUID userId) {
        List<Map<String, Object>> workspaceList = access.membershipsOfUser(userId).stream()
            .map(m -> Map.<String, Object>of(
                "workspaceId", m.workspaceId(), "name", m.name(), "role", m.role().name()))
            .toList();
        return Map.of("userId", userId, "workspaces", workspaceList);
    }
}
