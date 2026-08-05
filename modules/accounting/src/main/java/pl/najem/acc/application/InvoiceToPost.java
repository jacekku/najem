package pl.najem.acc.application;

import pl.najem.acc.domain.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An {@link InvoiceLine} that has been given the identity it will be known by.
 *
 * <p>The id is minted before the write because the event carries it too, and the event and the row
 * have to name the same obligation.
 */
public record InvoiceToPost(UUID invoiceId, Component component, BigDecimal amount) {
}
