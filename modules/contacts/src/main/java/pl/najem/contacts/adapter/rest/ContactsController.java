package pl.najem.contacts.adapter.rest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import pl.najem.contacts.WorkspaceContext;
import pl.najem.contacts.application.ContactDetails;
import pl.najem.contacts.application.ContactDirectory;
import pl.najem.contacts.application.ContactService;
import pl.najem.contacts.application.NewContact;
import pl.najem.contacts.application.RetentionHoldActiveException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/contacts")
public class ContactsController {

    public record RegisterContactRequest(String givenName, String surname, String email, String phone,
                                         String lawfulBasis, LocalDate infoClauseServedAt, LocalDate retainUntil) {
    }

    public record ContactDetailsRequest(String givenName, String surname, String email, String phone) {
    }

    private final ContactService contacts;
    private final ContactDirectory directory;
    private final Clock clock;

    public ContactsController(ContactService contacts, ContactDirectory directory, Clock clock) {
        this.contacts = contacts;
        this.directory = directory;
        this.clock = clock;
    }

    /**
     * Today, from the injected clock rather than the wall clock — @najem-reviewer's part-2 finding.
     * <p>
     * The bean is the composition root's since @najem-coordinator's {@code ca1b688}; before that it
     * was PM's, which meant contacts' notion of "today" silently depended on another module being
     * on the classpath.
     */
    private LocalDate today() {
        return LocalDate.now(clock);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> register(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                                      @RequestBody RegisterContactRequest request) {
        var contactId = contacts.register(new NewContact(workspaceId,
            new ContactDetails(request.givenName(), request.surname(), request.email(), request.phone()),
            request.lawfulBasis(), request.infoClauseServedAt(), request.retainUntil()));
        return Map.of("contactId", contactId);
    }

    @GetMapping("/{contactId}")
    public ContactDetails find(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                               @PathVariable UUID contactId) {
        return directory.find(workspace(workspaceId), contactId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping(params = "email")
    public List<UUID> findByEmail(@RequestHeader(name = "X-Workspace-Id", required = false) UUID workspaceId,
                                  @RequestParam String email) {
        return directory.findByEmail(workspace(workspaceId), email);
    }

    @PutMapping("/{contactId}/details")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correct(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                        @PathVariable UUID contactId, @RequestBody ContactDetailsRequest request) {
        contacts.correctDetails(workspaceId, contactId,
            new ContactDetails(request.givenName(), request.surname(), request.email(), request.phone()),
            today());
    }

    @DeleteMapping("/{contactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void erase(@RequestHeader("X-Workspace-Id") UUID workspaceId,
                      @PathVariable UUID contactId,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        contacts.erase(workspaceId, contactId, on == null ? today() : on);
    }

    @ExceptionHandler(RetentionHoldActiveException.class)
    public ResponseEntity<Map<String, String>> onHold(RetentionHoldActiveException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /**
     * Until Keycloak claims land, an absent header means the single dev workspace — on READS ONLY.
     * <p>
     * Every mutating endpoint requires the header instead, so a client that drops it gets a 400.
     * The asymmetry is deliberate: a read with no header shows you the wrong (empty) data and you
     * notice immediately, whereas a write with no header puts real data into a workspace nobody
     * named and returns a success. Erasing a contact, or releasing the retention hold that blocks
     * an erasure, must never be able to land somewhere the caller did not ask for.
     */
    static UUID workspace(UUID fromHeader) {
        return fromHeader == null ? WorkspaceContext.DEV_WORKSPACE_ID : fromHeader;
    }
}
