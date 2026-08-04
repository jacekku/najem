package pl.najem.reporting.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Just enough of a property to name it and group units under it. PM owns everything else about a
 * property; Reporting projects the two fields a board needs so it never has to reach across.
 */
@Component
public class PropertyProjection implements Projection {

    public static final String NAME = "property";

    private final JdbcTemplate jdbc;

    public PropertyProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Set<String> handles() {
        return Set.of("PropertyCreated");
    }

    @Override
    public void reset() {
        jdbc.update("delete from reporting_property");
    }

    @Override
    public void apply(FeedEntry entry) {
        var p = entry.payload();
        jdbc.update("""
            insert into reporting_property(property_id, workspace_id, address) values (?,?,?)
            on conflict (property_id) do update set address = excluded.address
            """,
            UUID.fromString(p.get("propertyId").asText()),
            UUID.fromString(p.get("workspaceId").asText()),
            p.path("address").asText(""));
    }
}
