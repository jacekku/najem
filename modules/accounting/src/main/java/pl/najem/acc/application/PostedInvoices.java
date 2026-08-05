package pl.najem.acc.application;

import java.util.List;
import java.util.UUID;

/**
 * What was charged, and what the manager should know about it. Warnings never block the posting —
 * this is an expert system: it flags, the manager decides.
 */
public record PostedInvoices(List<UUID> invoiceIds, List<WarningToRaise> warnings) {
}
