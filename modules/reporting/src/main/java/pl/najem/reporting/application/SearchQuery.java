package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * "Find me the thing I am thinking of" across everything Reporting projects — properties and units.
 * <p>
 * <b>It does not search people, and it structurally cannot.</b> Reporting has never seen a name:
 * events carry identifiers only and the PII lookaside keeps every personal field in
 * {@code contacts_person}, which is what makes erasure a row deletion. A projection holding contact
 * names here would be a second copy that outlives the deletion — the right-to-be-forgotten defect,
 * built deliberately. So the person half of search is served by contacts, at
 * {@code GET /api/contacts/search}, from the table erasure empties.
 * <p>
 * <b>Every branch of a union is a separate chance to forget the tenant predicate.</b>
 * @najem-reviewer named this endpoint at najem-build seq 350 as the one they expected to leak, and
 * the reason is the shape rather than the code: a workspace predicate missed on one branch is
 * invisible while the other branches return correctly scoped rows, so the endpoint looks right in
 * every test that does not probe that specific kind. Each kind is therefore asserted separately.
 */
@Component
public class SearchQuery {

    /**
     * {@code kind} is {@code "property"} or {@code "unit"}. {@code propertyId} is the property
     * itself for a property hit and the unit's parent for a unit hit — a hit is only useful if the
     * caller can navigate to it, and {@code /api/reporting/units} takes a {@code propertyId}.
     * <p>
     * No person, no owner, no tenant: {@code label} is an address and a unit name, both facts about
     * a building.
     */
    public record Hit(String kind, UUID id, String label, UUID propertyId) {
    }

    /** A search box is a browse aid, not an export. Enough rows to find something, not to harvest. */
    private static final int LIMIT = 50;

    private final JdbcTemplate jdbc;

    public SearchQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An empty or blank term returns nothing rather than everything. "Show me the whole portfolio"
     * is what {@code GET /api/reporting/properties} is for, and a search box that dumps every row on
     * an accidental submit is how a list endpoint acquires a second, unpaged identity.
     */
    public List<Hit> search(UUID workspaceId, String term) {
        if (term == null || term.isBlank()) {
            return List.of();
        }
        var pattern = "%" + escapeLike(term.strip()) + "%";
        return jdbc.query("""
            select kind, id, label, property_id from (
                select 'property' as kind, pr.property_id as id, pr.address as label,
                       pr.property_id as property_id
                from reporting_property pr
                where pr.workspace_id = ? and pr.address ilike ? escape '\\'
              union all
                -- pr.workspace_id = u.workspace_id is REDUNDANT and its mutation SURVIVES: removing
                -- it leaves all 19 tests green, because property_id is a globally unique primary
                -- key, so joining on it alone can only ever reach the one correct row. Kept as the
                -- predicate that stops being redundant the day property_id is scoped per tenant —
                -- but stated here rather than merely asserted, because a test never observed
                -- failing is indistinguishable from a test that cannot fail.
                select 'unit', u.unit_id, pr.address || ' — ' || u.name, u.property_id
                from reporting_unit_state u
                join reporting_property pr
                  on pr.property_id = u.property_id and pr.workspace_id = u.workspace_id
                where u.workspace_id = ? and not u.removed and u.name ilike ? escape '\\'
            ) hits
            order by kind, label
            limit %d
            """.formatted(LIMIT),
            (rs, i) -> new Hit(
                rs.getString("kind"),
                UUID.fromString(rs.getString("id")),
                rs.getString("label"),
                UUID.fromString(rs.getString("property_id"))),
            workspaceId, pattern, workspaceId, pattern);
    }

    /**
     * A term containing {@code %} is a term, not a wildcard. Without this a caller typing {@code %}
     * matches every row in their workspace — not a boundary breach, but a list endpoint by accident,
     * which is precisely what the blank-term guard above refuses.
     */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
