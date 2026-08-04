package pl.najem.fakebank;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * Generates the named bank-statement scenarios that accounting's matching ladder is tested against.
 *
 * <p>Pure and deterministic by contract: no clock, no randomness, no state. The same request always
 * produces byte-identical lines, so re-seeding is idempotent from a consumer's point of view.
 */
@Component
public class ScenarioCatalog {

    private static final String CREDIT = "CRDT";
    private static final String DEBIT = "DBIT";
    private static final String PLN = "PLN";

    private static final Set<String> NAMES = Set.of(
        "on-time", "late", "partial", "partial-then-topup", "overpay",
        "wrong-reference", "no-reference", "duplicate", "reversal", "lump-sum",
        "lump-sum-two-tenancies",
        "third-party-payer", "outgoing-debit", "foreign-currency");

    public Set<String> names() {
        return NAMES;
    }

    public List<BankTransactionDto> generate(ScenarioRequest request) {
        return switch (request.name()) {
            case "on-time" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 0, 0));
            case "late" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 6, 8));
            case "partial" -> List.of(
                credit(request, 0, percentOf(request.amount(), 60), request.reference(), 0, 0));
            case "partial-then-topup" -> {
                BigDecimal first = percentOf(request.amount(), 60);
                yield List.of(
                    credit(request, 0, first, request.reference(), 0, 0),
                    credit(request, 1, request.amount().subtract(first), request.reference(), 4, 4));
            }
            case "overpay" -> List.of(
                credit(request, 0, percentOf(request.amount(), 120), request.reference(), 0, 0));
            case "wrong-reference" -> List.of(
                credit(request, 0, request.amount(), mangle(request.reference()), 0, 0));
            case "no-reference" -> List.of(
                credit(request, 0, request.amount(), "", 0, 0));
            case "duplicate" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 0, 0),
                credit(request, 1, request.amount(), request.reference(), 0, 0));
            case "reversal" -> List.of(
                credit(request, 0, request.amount(), request.reference(), 0, 0),
                line(request, 1, request.amount(), "ZWROT " + request.reference(), 3, 3,
                    DEBIT, PLN, tenantName(request.reference()), syntheticIban(request.reference())));
            case "lump-sum" -> List.of(
                credit(request, 0, request.amount().multiply(BigDecimal.TWO),
                    request.reference() + " " + request.reference() + "/2", 0, 0));
            case "lump-sum-two-tenancies" -> List.of(
                credit(request, 0, request.amount().multiply(BigDecimal.TWO),
                    request.reference() + " " + secondReferenceOf(request), 0, 0));
            case "third-party-payer" -> List.of(
                line(request, 0, request.amount(), "", 0, 0, CREDIT, PLN,
                    "ANNA KOWALSKA", syntheticIban("payer:" + request.reference())));
            case "outgoing-debit" -> List.of(
                line(request, 0, new BigDecimal("287.43"), "OPLATA ZA MEDIA", 1, 1,
                    DEBIT, PLN, "PGNIG OBROT DETALICZNY", syntheticIban("utility")));
            case "foreign-currency" -> List.of(
                line(request, 0, request.amount(), request.reference(), 0, 0, CREDIT, "EUR",
                    tenantName(request.reference()), syntheticIban(request.reference())));
            default -> throw new UnknownScenarioException(request.name());
        };
    }

    /**
     * One credit line paid from the tenant's own account.
     *
     * @param index         position within the scenario; drives the deterministic external id
     * @param bookingOffset days after the anchor date the line is booked
     * @param valueOffset   days after the anchor date the line is valued
     */
    private BankTransactionDto credit(ScenarioRequest request, int index, BigDecimal amount,
                                      String title, int bookingOffset, int valueOffset) {
        return line(request, index, amount, title, bookingOffset, valueOffset, CREDIT, PLN,
            tenantName(request.reference()), syntheticIban(request.reference()));
    }

    private BankTransactionDto line(ScenarioRequest request, int index, BigDecimal amount, String title,
                                    int bookingOffset, int valueOffset, String indicator, String currency,
                                    String counterpartyName, String counterpartyIban) {
        String externalId = externalId(request.name(), request.reference(), index);
        return new BankTransactionDto(
            externalId,
            amount.setScale(2, RoundingMode.HALF_UP),
            title,
            request.anchorDate().plusDays(bookingOffset),
            counterpartyName,
            counterpartyIban,
            bankReference(externalId),
            request.anchorDate().plusDays(valueOffset),
            indicator,
            currency);
    }

    /**
     * The second tenancy's reference, which the caller must supply.
     *
     * <p>Deliberately not derived from the first. A derived reference would share a prefix or a
     * segment with it, and a matching rule that keyed on that similarity would appear to handle a
     * cross-tenancy transfer while having actually recognised one tenancy twice — the fixture would
     * pass for the wrong reason.
     */
    private static String secondReferenceOf(ScenarioRequest request) {
        if (request.secondReference() == null || request.secondReference().isBlank()) {
            throw new IllegalArgumentException(
                "lump-sum-two-tenancies needs a secondReference: it is a transfer covering two "
                    + "independent tenancies, and the second one cannot be invented");
        }
        return request.secondReference();
    }

    private static BigDecimal percentOf(BigDecimal amount, int percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static String externalId(String scenario, String reference, int index) {
        return scenario + "/" + sanitise(reference) + "/" + index;
    }

    private static String sanitise(String value) {
        return value.replaceAll("[^A-Za-z0-9-]", "-");
    }

    /** A reference as a careless payer would type it: separators lost, case lost. */
    private static String mangle(String reference) {
        return reference.replace("/", " ").toLowerCase();
    }

    /** Bank-side reference, deliberately unrelated to the tenant's title reference. */
    private static String bankReference(String seed) {
        return "BNP" + String.format("%08d", digitsOf(seed, 100_000_000L));
    }

    /** A stable synthetic account for a tenant: same reference always yields the same IBAN. */
    private static String syntheticIban(String reference) {
        return "PL" + String.format("%026d", digitsOf("iban:" + reference, 1_000_000_000_000L));
    }

    private static String tenantName(String reference) {
        return "NAJEMCA " + sanitise(reference);
    }

    /**
     * A non-negative value derived from the seed. {@link String#hashCode()} is specified by the
     * language, so this is reproducible across JVMs and machines — which the determinism contract needs.
     */
    private static long digitsOf(String seed, long bound) {
        return Math.floorMod((long) seed.hashCode(), bound);
    }
}
