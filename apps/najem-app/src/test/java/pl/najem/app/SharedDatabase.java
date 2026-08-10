package pl.najem.app;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres for every application test in this module, started once and never stopped.
 *
 * <p><b>Why one container rather than one per class.</b> Each class used to own a
 * {@code @Container static PostgreSQLContainer}, which the Testcontainers extension stops in
 * {@code afterAll}. Spring's context cache does not stop with it: the context stays cached for the
 * lifetime of the JVM, and so do {@code OutboxDispatcher} (every 500ms) and
 * {@code ProjectionRunner} (every 1000ms). From the moment a class finished, its context's
 * schedulers polled a container that was no longer listening, and every poll blocked on Hikari's
 * connection timeout — serialised, because {@code scheduling-1} is one thread. Measured cost was
 * roughly 27 seconds of dead time per finished class, which is where 9 of this module's 11-minute
 * build went.
 *
 * <p>Not stopping it is the point, not an oversight. Testcontainers' Ryuk sidecar reaps the
 * container when the JVM exits, so nothing is leaked; what is avoided is the window in which a
 * live context points at a dead database.
 *
 * <p><b>Why sharing one database is safe.</b> NAJEM is multi-tenant and every read a screen makes
 * is scoped to a workspace, so a class is isolated by creating its own agency rather than its own
 * database. The subtler invariant is the one {@link pl.najem.app.web.WebWorkspaceResolver} enforces:
 * a user with more than one membership and no choice made gets {@code ChoiceRequiredException}
 * rather than a screen. That is a fact about a <em>subject</em>, not about the database — so
 * classes keep their own distinct {@code najem.bootstrap.operator-subject}, each operator holds
 * exactly one membership, and neighbouring agencies are invisible to it. Sharing the container
 * changes neither.
 *
 * <p>Extending this class is what supplies the datasource; there is deliberately no
 * {@code @ServiceConnection} field left on the subclasses, because two sources for one datasource
 * is how one of them silently stops being the one in use.
 */
public abstract class SharedDatabase {

    /**
     * {@code max_connections} is raised because one server now answers every context in the JVM.
     * Spring caches a context per distinct configuration and never evicts one, so all of them hold
     * their pools open at once — with per-class containers each pool had a server to itself, and
     * sharing one made the total visible for the first time as {@code FATAL: sorry, too many
     * clients already} during Flyway. Headroom rather than an exact fit, so that adding a test
     * class is not quietly a capacity decision.
     */
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16").withCommand("postgres", "-c", "max_connections=300");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Boot's default is 10, sized for a server serving real concurrent traffic. A MockMvc test
        // drives one request at a time; the only other claimants are OutboxDispatcher and
        // ProjectionRunner, one thread each. Three is the honest number, and the saving is
        // multiplied by every cached context that outlives the class that built it.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
    }

    /**
     * For the one test that assembles its own {@code SpringApplicationBuilder} rather than taking a
     * context from the framework, and so has to pass the datasource as {@code --arg}s.
     * {@code SecurityFailsClosedTest} cannot extend this class usefully — what it asserts is that a
     * context <em>refuses</em> to be built — but it should still share the container.
     */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }
}
