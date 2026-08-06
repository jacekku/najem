package pl.najem.acc.adapter.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.najem.acc.application.ArrearsBoardProjection;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.najem.contracts.web.ActingWorkspace;

@RestController
@RequestMapping("/api/acc")
public class BoardController {

    private final ArrearsBoardProjection board;

    public BoardController(ArrearsBoardProjection board) {
        this.board = board;
    }

    /**
     * The arrears board for one workspace. The header is required, as on every read: a board served
     * to a caller who named no agency is another agency's portfolio, tenancy by tenancy, with its
     * arrears next to it.
     */
    @GetMapping("/board")
    public List<Map<String, Object>> board(@ActingWorkspace UUID workspaceId) {
        return board.forWorkspace(workspaceId).stream()
            .map(row -> Map.<String, Object>of(
                "tenancyId", row.tenancyId(),
                "status", row.colour().wireName(),
                // The colour says the art. 11 clock is running; this says how far it has run.
                // Termination becomes available at three full periods, so a screen with only the
                // colour can report that something is wrong but not what may lawfully be done.
                "fullPeriodsInArrears", row.fullPeriodsInArrears()))
            .toList();
    }
}
