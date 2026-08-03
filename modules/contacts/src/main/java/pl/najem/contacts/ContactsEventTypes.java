package pl.najem.contacts;

import org.springframework.stereotype.Component;
import pl.najem.contacts.domain.ContactRegistered;
import pl.najem.eventstore.EventTypeRegistry;

@Component
public class ContactsEventTypes {

    public ContactsEventTypes(EventTypeRegistry registry) {
        register(registry);
    }

    public static void register(EventTypeRegistry registry) {
        registry.register(ContactRegistered.class);
    }
}
