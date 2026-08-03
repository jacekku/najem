package pl.najem.um;

import org.springframework.stereotype.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.um.domain.UserLinkedToContact;
import pl.najem.um.domain.UserRegistered;
import pl.najem.um.domain.WorkspaceCreated;
import pl.najem.um.domain.WorkspaceRenamed;

@Component
public class UmEventTypes {

    public UmEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(WorkspaceCreated.class);
        registry.register(WorkspaceRenamed.class);
        registry.register(UserRegistered.class);
        registry.register(UserLinkedToContact.class);
    }
}
