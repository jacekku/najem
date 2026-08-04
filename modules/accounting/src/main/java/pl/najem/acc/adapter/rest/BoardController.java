package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.ArrearsBoardQuery;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/acc")
public class BoardController {

    private final ArrearsBoardQuery board;

    public BoardController(ArrearsBoardQuery board) {
        this.board = board;
    }

    /**
     * The arrears board for one workspace. The header is required, as on every read: a board served
     * to a caller who named no agency is another agency's portfolio, tenancy by tenancy, with its
     * arrears next to it.
     */
    @GetMapping("/board")
    public List<Map<String, Object>> board(@RequestHeader("X-Workspace-Id") UUID workspaceId) {
        return board.forWorkspace(workspaceId).stream()
            .map(row -> Map.<String, Object>of(
                "tenancyId", row.tenancyId(),
                "status", row.colour().wireName()))
            .toList();
    }
}
