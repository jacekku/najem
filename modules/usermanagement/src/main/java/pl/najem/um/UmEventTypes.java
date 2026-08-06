package pl.najem.um;

import org.springframework.stereotype.Component;
import pl.najem.eventstore.EventTypeRegistry;
import pl.najem.um.domain.events.InvitationAccepted;
import pl.najem.um.domain.events.InvitationRevoked;
import pl.najem.um.domain.events.MemberInvited;
import pl.najem.um.domain.events.MemberJoined;
import pl.najem.um.domain.events.MemberRemoved;
import pl.najem.um.domain.events.MemberRoleChanged;
import pl.najem.um.domain.events.UserLinkedToContact;
import pl.najem.um.domain.events.UserRegistered;
import pl.najem.um.domain.events.WorkspaceCreated;
import pl.najem.um.domain.events.WorkspaceRenamed;

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
        registry.register(MemberRoleChanged.class);
        registry.register(MemberRemoved.class);
        registry.register(MemberInvited.class);
        registry.register(MemberJoined.class);
        registry.register(InvitationRevoked.class);
        registry.register(InvitationAccepted.class);
    }
}
