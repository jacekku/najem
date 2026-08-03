package pl.najem.contracts.events;

/** Implemented by consuming modules; invoked directly by the outbox dispatcher. */
public interface IntegrationEventHandler<T extends IntegrationEvent> {

    Class<T> eventType();

    void handle(T event);
}
