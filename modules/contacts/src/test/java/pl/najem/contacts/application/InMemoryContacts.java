package pl.najem.contacts.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The people table and its tombstone, in a map.
 *
 * <p>Written to fail the same ways the SQL does rather than to agree with it (rule 14). Three
 * mechanisms are modelled deliberately:
 *
 * <ul>
 *   <li><b>Workspace scoping is a filter, not an exception.</b> Every statement in
 *       {@code PostgresContactRepository} carries {@code and workspace_id = ?}, so a foreign id is
 *       a read that finds nothing or an update that changes nothing — silent by design. A double
 *       that threw on a mismatch would turn every gate test green for the wrong reason.
 *   <li><b>{@code delete} reports rows.</b> The erase path branches on it to tell a first erasure
 *       from an idempotent repeat, so returning a constant would make that branch untestable
 *       (rule 13).
 *   <li><b>{@code search} escapes.</b> The SQL passes {@code %} and {@code _} through an
 *       {@code escape '\'} clause so a term stays a term. Here the term is compared literally,
 *       which is the same observable behaviour reached the only way a map can reach it.
 * </ul>
 *
 * <p>What it does not model is collation: the SQL orders by surname then given name under the
 * database's collation, and this orders under {@code Locale.ROOT}. Polish diacritics sort
 * differently in the two, so a test asserting the order of Ł against L is a test the container tier
 * must own (rule 16 — where they disagree, the database is right).
 */
public class InMemoryContacts implements ContactRepository {

    private record Row(UUID workspaceId, ContactDetails details, String lawfulBasis,
                       LocalDate infoClauseServedAt, LocalDate retainUntil) {
    }

    private final Map<UUID, Row> people = new LinkedHashMap<>();
    private final Map<UUID, UUID> erased = new LinkedHashMap<>();

    @Override
    public Optional<ContactDetails> find(UUID workspaceId, UUID contactId) {
        return mine(workspaceId, contactId).map(Row::details);
    }

    private Optional<Row> mine(UUID workspaceId, UUID contactId) {
        return Optional.ofNullable(people.get(contactId))
            .filter(row -> row.workspaceId().equals(workspaceId));
    }

    @Override
    public List<ContactMatch> search(UUID workspaceId, String term, int limit) {
        var needle = term.toLowerCase(Locale.ROOT);
        var hits = new ArrayList<ContactMatch>();
        people.forEach((contactId, row) -> {
            if (!row.workspaceId().equals(workspaceId)) {
                return;
            }
            var given = row.details().givenName();
            var surname = row.details().surname();
            if (contains(given, needle) || contains(surname, needle)
                || contains(given + " " + surname, needle)) {
                hits.add(new ContactMatch(contactId, given, surname, row.details().email()));
            }
        });
        hits.sort((a, b) -> {
            int bySurname = a.surname().compareToIgnoreCase(b.surname());
            return bySurname != 0 ? bySurname : a.givenName().compareToIgnoreCase(b.givenName());
        });
        return List.copyOf(hits.subList(0, Math.min(limit, hits.size())));
    }

    /** Literal containment — the counterpart of the SQL's {@code escape '\'}, not of {@code ilike}. */
    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    @Override
    public List<UUID> findByEmail(UUID workspaceId, String email) {
        return people.entrySet().stream()
            .filter(e -> e.getValue().workspaceId().equals(workspaceId))
            .filter(e -> java.util.Objects.equals(e.getValue().details().email(), email))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
    }

    @Override
    public void insert(UUID contactId, NewContact contact) {
        people.put(contactId, new Row(contact.workspaceId(), contact.details(), contact.lawfulBasis(),
            contact.infoClauseServedAt(), contact.retainUntil()));
    }

    @Override
    public void updateDetails(UUID workspaceId, UUID contactId, ContactDetails details) {
        mine(workspaceId, contactId).ifPresent(row ->
            people.put(contactId, new Row(row.workspaceId(), details, row.lawfulBasis(),
                row.infoClauseServedAt(), row.retainUntil())));
    }

    @Override
    public int delete(UUID workspaceId, UUID contactId) {
        if (mine(workspaceId, contactId).isEmpty()) {
            return 0;
        }
        people.remove(contactId);
        return 1;
    }

    @Override
    public boolean isKnownOrErased(UUID workspaceId, UUID contactId) {
        return mine(workspaceId, contactId).isPresent()
            || workspaceId.equals(erased.get(contactId));
    }

    @Override
    public void logErasure(UUID workspaceId, UUID contactId, LocalDate erasedOn) {
        erased.put(contactId, workspaceId);
    }

    /** For {@link InMemoryErasureDue}, which reads the people table the way the SQL joins to it. */
    List<UUID> retainedUntilOnOrBefore(UUID workspaceId, LocalDate asOf) {
        return people.entrySet().stream()
            .filter(e -> e.getValue().workspaceId().equals(workspaceId))
            .filter(e -> e.getValue().retainUntil() != null
                && !e.getValue().retainUntil().isAfter(asOf))
            .sorted(java.util.Comparator.comparing(e -> e.getValue().retainUntil()))
            .map(Map.Entry::getKey)
            .toList();
    }
}
