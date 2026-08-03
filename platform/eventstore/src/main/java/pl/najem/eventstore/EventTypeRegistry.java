package pl.najem.eventstore;

import java.util.HashMap;
import java.util.Map;

/** Maps event class simple names to classes; modules register their events at startup. */
public class EventTypeRegistry {

    private final Map<String, Class<?>> byName = new HashMap<>();

    public void register(Class<?> type) {
        byName.put(type.getSimpleName(), type);
    }

    public Class<?> resolve(String name) {
        Class<?> type = byName.get(name);
        if (type == null) {
            throw new IllegalArgumentException("Unknown event type: " + name);
        }
        return type;
    }

    public String nameOf(Class<?> type) {
        return type.getSimpleName();
    }
}
