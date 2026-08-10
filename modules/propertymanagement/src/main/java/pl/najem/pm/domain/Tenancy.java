package pl.najem.pm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The agreement arc: people, money terms, lifecycle Reserved -> Active -> Ended.
 *
 * <p>Transitions are loosely constrained by design (expert system). Only the lifecycle
 * ordering is enforced; every business rule about amounts and legality warns instead of
 * blocking, so a manager who knows better is never stopped by the software.
 */
public class Tenancy {

    public enum State { RESERVED, ACTIVE, CANCELLED, ENDED }

    private UUID id;
    private UUID workspaceId;
    private UUID unitId;
    private final List<UUID> tenantContactIds = new ArrayList<>();
    private final List<UUID> guarantorContactIds = new ArrayList<>();
    private LocalDate startDate;
    private LocalDate endDate;
    private LegalForm legalForm;
    private MonthlyAmount monthly;
    private int rentDay;
    private BigDecimal depositAmount;
    private String paymentReference;
    private State state;
    private final Map<String, ChecklistPhase> checklistItems = new LinkedHashMap<>();
    private final Set<String> completedItems = new HashSet<>();
    private final Map<ChecklistPhase, HandoverProtocol> handoverProtocols =
        new EnumMap<>(ChecklistPhase.class);
    /** Set by TenancyDocumentAttached(NOTARIAL_DECLARATION) — Task 9. */
    private boolean notarialDeclarationAttached;
    private final Map<LocalDate, RentChange> pendingChanges = new LinkedHashMap<>();
    private final List<String> comments = new ArrayList<>();
    private LocalDate insuranceExpiry;
    /** Fields corrected after Accounting was told about them — see warnings(). */
    private final Set<String> correctedAfterPublication = new LinkedHashSet<>();
    /** Set by the standing termination notice; a later notice replaces it. */
    private LocalDate noticeEffectiveDate;
    private boolean endingSoon;

    /**
     * What a correction may touch. Deliberately short: everything else is either a change of
     * terms (which has its own event and its own effective date) or immutable identity.
     */
    private static final Set<String> CORRECTABLE =
        Set.of("paymentReference", "rentDay", "startDate", "monthlyTotal");

    /** Correctable fields Accounting was already told about at activation. */
    private static final Set<String> PUBLISHED_TO_ACCOUNTING =
        Set.of("paymentReference", "monthlyTotal");

    private Tenancy() {
    }

    public static List<Object> reserve(ReserveTenancy c) {
        if (c.tenantContactIds().isEmpty()) {
            throw new IllegalArgumentException("A tenancy needs at least one tenant contact");
        }
        // Rule 7, fail closed. legalForm decides the statutory deposit cap AND whether the
        // notarial declaration gates auto-activation, so a default would silently pick the
        // weakest of both controls on a tenancy that looks entirely valid.
        if (c.legalForm() == null) {
            throw new IllegalArgumentException("A tenancy needs an explicit legal form");
        }
        return List.of(new TenancyEvents.TenancyReserved(c.workspaceId(), c.tenancyId(), c.unitId(),
            List.copyOf(c.tenantContactIds()), List.copyOf(c.guarantorContactIds()),
            c.startDate(), c.term().endDate(), c.legalForm(), c.monthly(), c.rentDay(),
            c.depositAmount(), c.paymentReference()));
    }

    public List<Object> addTenant(UUID contactId) {
        return List.of(new TenancyEvents.TenantAddedToTenancy(workspaceId, id, contactId));
    }

    public List<Object> removeTenant(UUID contactId) {
        return List.of(new TenancyEvents.TenantRemovedFromTenancy(workspaceId, id, contactId));
    }

    public List<Object> cancelReservation(String reason) {
        if (state != State.RESERVED) {
            throw new NotReservedException(
                "Only a reserved tenancy can be cancelled (state: " + state + ")");
        }
        return List.of(new TenancyEvents.TenancyReservationCancelled(workspaceId, id, reason));
    }

    public List<Object> activate(LocalDate on) {
        if (state != State.RESERVED) {
            throw new IllegalStateException(
                "Only a reserved tenancy can be activated (state: " + state + ")");
        }
        return List.of(new TenancyEvents.TenancyActivated(workspaceId, id, on));
    }

    public List<Object> addChecklistItem(String key, ChecklistPhase phase) {
        return List.of(new TenancyEvents.ChecklistItemAdded(workspaceId, id, key, phase));
    }

    /** A mistyped key must fail loudly, not silently complete nothing. */
    public List<Object> completeChecklistItem(String key) {
        if (!checklistItems.containsKey(key)) {
            throw new IllegalArgumentException("No checklist item '" + key + "' on tenancy " + id);
        }
        return List.of(new TenancyEvents.ChecklistItemCompleted(workspaceId, id, key));
    }

    /** An empty checklist is complete — MVP ships no predefined items; managers add their own. */
    public boolean checklistComplete(ChecklistPhase phase) {
        return checklistItems.entrySet().stream()
            .filter(entry -> entry.getValue() == phase)
            .allMatch(entry -> completedItems.contains(entry.getKey()));
    }

    public List<Object> recordHandoverProtocol(HandoverProtocol protocol) {
        // Rule 7, fail closed. The phase decides whether the move-out protocol is published to
        // Accounting; a defaulted one starts no deposit-settlement clock and says nothing.
        if (protocol.type() == null) {
            throw new IllegalArgumentException("A handover protocol needs an explicit phase");
        }
        return List.of(new TenancyEvents.HandoverProtocolRecorded(workspaceId, id, protocol));
    }

    public Optional<HandoverProtocol> handoverProtocol(ChecklistPhase phase) {
        return Optional.ofNullable(handoverProtocols.get(phase));
    }

    /**
     * An instytucjonalny tenancy needs the tenant's notarial submission-to-execution declaration
     * before it can activate UNATTENDED (v1.1 amendment). Manual activation stays possible and is
     * the manager's call — this gate only stops the process manager from doing it silently.
     */
    public boolean autoActivationAllowed() {
        return legalForm != LegalForm.INSTYTUCJONALNY || notarialDeclarationAttached;
    }

    public List<Object> addComment(String text) {
        return List.of(new TenancyEvents.TenancyCommentAdded(workspaceId, id, text));
    }

    /**
     * Fixing what a field should always have said — a typo in a payment reference, a start date
     * entered a week out. Not a change of terms: that is a rent change or an annex.
     *
     * <p>An unknown key is rejected rather than ignored, for the same reason a mistyped checklist
     * key is: a correction that silently corrects nothing looks exactly like one that worked.
     */
    public List<Object> correctDetails(Map<String, String> corrections) {
        corrections.keySet().stream()
            .filter(key -> !CORRECTABLE.contains(key))
            .findFirst()
            .ifPresent(key -> {
                throw new IllegalArgumentException("Cannot correct '" + key + "' on tenancy " + id
                    + " — correctable fields are " + CORRECTABLE);
            });
        return List.of(new TenancyEvents.TenancyDetailsCorrected(workspaceId, id,
            Map.copyOf(corrections)));
    }

    public List<Object> attachDocument(DocType type, String s3Ref, LocalDate validFrom,
                                       LocalDate validTo, LocalDate date) {
        return List.of(new TenancyEvents.TenancyDocumentAttached(workspaceId, id, type, s3Ref,
            validFrom, validTo, date));
    }

    /** The latest policy's expiry — a renewal supersedes the policy it replaces. */
    public Optional<LocalDate> insuranceExpiry() {
        return Optional.ofNullable(insuranceExpiry);
    }

    public List<String> comments() {
        return List.copyOf(comments);
    }

    public List<Object> scheduleRentChange(LocalDate decidedOn, LocalDate effectiveFrom,
                                           MonthlyAmount newMonthly, ChangeType type) {
        return List.of(new TenancyEvents.RentChangeScheduled(workspaceId, id, decidedOn,
            effectiveFrom, newMonthly, type));
    }

    public List<Object> cancelRentChange(LocalDate effectiveFrom) {
        return List.of(new TenancyEvents.RentChangeCancelled(workspaceId, id, effectiveFrom));
    }

    /** Only applies a change that still stands — an edited or cancelled one sends nothing. */
    public List<Object> applyRentChange(LocalDate effectiveFrom) {
        RentChange change = pendingChanges.get(effectiveFrom);
        if (change == null) {
            throw new IllegalStateException(
                "No rent change pending for " + effectiveFrom + " on tenancy " + id);
        }
        return List.of(new TenancyEvents.RentChangeApplied(workspaceId, id, effectiveFrom,
            change.monthly(), change.type()));
    }

    /**
     * Notice moves the end date; it does not end the tenancy. A later notice supersedes an
     * earlier one — parties renegotiate, and the last word is the one that stands.
     */
    public List<Object> giveTerminationNotice(String ground, LocalDate noticeDate,
                                              LocalDate effectiveDate, String noticeDocRef) {
        if (state != State.ACTIVE && state != State.RESERVED) {
            throw new IllegalStateException(
                "Notice can only be given on a live tenancy (state: " + state + ")");
        }
        return List.of(new TenancyEvents.TerminationNoticeGiven(workspaceId, id, ground,
            noticeDate, effectiveDate, noticeDocRef));
    }

    /**
     * The date the tenancy is currently expected to stop: the standing notice's effective date
     * when there is one, otherwise the agreed term end. Null for an indefinite tenancy nobody
     * has given notice on — there genuinely is no end date, and the ending-soon process reads
     * that as nothing to arm rather than as an error.
     */
    public LocalDate effectiveEndDate() {
        return noticeEffectiveDate != null ? noticeEffectiveDate : endDate;
    }

    /**
     * Ending is allowed from RESERVED so a mistaken reservation can be annulled without first
     * being activated. Everything else about the ending is the manager's call — PM records the
     * reason, it does not adjudicate it.
     */
    public List<Object> end(EndTenancy c) {
        if (state != State.ACTIVE && state != State.RESERVED) {
            throw new IllegalStateException(
                "Only a live tenancy can be ended (state: " + state + ")");
        }
        return List.of(new TenancyEvents.TenancyEnded(workspaceId, id, c.endDate(),
            c.vacateDate(), c.reason(), c.comment(), c.backToMarket()));
    }

    public List<Object> flagEndingSoon() {
        return List.of(new TenancyEvents.TenancyEndingSoon(workspaceId, id, effectiveEndDate()));
    }

    public boolean endingSoon() {
        return endingSoon;
    }

    public Optional<RentChange> pendingRentChange(LocalDate effectiveFrom) {
        return Optional.ofNullable(pendingChanges.get(effectiveFrom));
    }

    /** The earliest change still pending — the process manager arms against this one. */
    public Optional<RentChange> nextPendingRentChange() {
        return pendingChanges.values().stream().min(Comparator.comparing(RentChange::effectiveFrom));
    }

    /**
     * Soft checks only — the manager confirms; nothing here blocks. The authoritative
     * statutory gate lives in the Tenancy Accounting ACL; these exist to catch the mistake
     * at data entry, where the manager actually is.
     */
    public Warnings warnings() {
        var warnings = new Warnings();
        if (monthly.breakdown() != null
                && monthly.breakdown().sum().compareTo(monthly.total()) != 0) {
            warnings.add("Breakdown totals " + plain(monthly.breakdown().sum())
                + " but monthly total is " + plain(monthly.total()));
        }
        if (depositAmount != null) {
            var cap = monthly.total().multiply(new BigDecimal(legalForm.depositCapMultiplier()));
            if (depositAmount.compareTo(cap) > 0) {
                warnings.add("Deposit " + plain(depositAmount) + " exceeds the statutory "
                    + legalForm.depositCapMultiplier() + "x cap for " + legalForm
                    + " (" + plain(cap) + ")");
            }
        }
        // art. 8a/9: a unilateral increase needs 3 months' notice. Warn, don't block —
        // the ACL's compliance seat is the authoritative gate.
        pendingChanges.values().stream()
            .filter(change -> change.type() == ChangeType.UNILATERAL_INCREASE)
            .filter(change -> change.decidedOn().plusMonths(3).isAfter(change.effectiveFrom()))
            .forEach(change -> warnings.add("Unilateral increase effective "
                + change.effectiveFrom() + " gives less than 3 months notice (art. 8a/9)"));
        // PM cannot fix this alone — it needs a contract record — so it must not pass silently.
        correctedAfterPublication.forEach(field -> warnings.add(
            "Corrected " + field + " after Accounting was told the old value at activation; "
                + "their ledger still holds it"));
        if (endDate != null && startDate.plusYears(10).isBefore(endDate)) {
            warnings.add("Fixed term longer than 10 years");
        }
        return warnings;
    }

    private static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    public static Tenancy from(List<Object> events) {
        var tenancy = new Tenancy();
        events.forEach(tenancy::apply);
        return tenancy;
    }

    private void apply(Object event) {
        switch (event) {
            case TenancyEvents.TenancyReserved e -> {
                id = e.tenancyId();
                workspaceId = e.workspaceId();
                unitId = e.unitId();
                tenantContactIds.addAll(e.tenantContactIds());
                guarantorContactIds.addAll(e.guarantorContactIds());
                startDate = e.startDate();
                endDate = e.endDate();
                legalForm = e.legalForm();
                monthly = e.monthly();
                rentDay = e.rentDay();
                depositAmount = e.depositAmount();
                paymentReference = e.paymentReference();
                state = State.RESERVED;
            }
            case TenancyEvents.TenantAddedToTenancy e -> tenantContactIds.add(e.contactId());
            case TenancyEvents.TenantRemovedFromTenancy e -> tenantContactIds.remove(e.contactId());
            case TenancyEvents.TenancyReservationCancelled e -> state = State.CANCELLED;
            case TenancyEvents.TenancyActivated e -> state = State.ACTIVE;
            case TenancyEvents.ChecklistItemAdded e -> checklistItems.put(e.key(), e.phase());
            case TenancyEvents.ChecklistItemCompleted e -> completedItems.add(e.key());
            case TenancyEvents.HandoverProtocolRecorded e ->
                handoverProtocols.put(e.protocol().type(), e.protocol());
            case TenancyEvents.RentChangeScheduled e -> pendingChanges.put(e.effectiveFrom(),
                new RentChange(e.decidedOn(), e.effectiveFrom(), e.monthly(), e.type()));
            case TenancyEvents.RentChangeCancelled e -> pendingChanges.remove(e.effectiveFrom());
            case TenancyEvents.RentChangeApplied e -> {
                monthly = e.monthly();
                pendingChanges.remove(e.effectiveFrom());
            }
            case TenancyEvents.TenancyCommentAdded e -> comments.add(e.text());
            case TenancyEvents.TenancyDetailsCorrected e -> applyCorrections(e.corrections());
            case TenancyEvents.TenancyDocumentAttached e -> {
                if (e.docType() == DocType.NOTARIAL_DECLARATION) {
                    notarialDeclarationAttached = true;
                }
                if (e.docType() == DocType.INSURANCE_POLICY && e.validTo() != null) {
                    insuranceExpiry = e.validTo();
                }
            }
            case TenancyEvents.TerminationNoticeGiven e -> noticeEffectiveDate = e.effectiveDate();
            case TenancyEvents.TenancyEndingSoon e -> endingSoon = true;
            case TenancyEvents.TenancyEnded e -> state = State.ENDED;
            default -> throw new UnknownEventException(event);
        }
    }

    private void applyCorrections(Map<String, String> corrections) {
        corrections.forEach((key, value) -> {
            switch (key) {
                case "paymentReference" -> paymentReference = value;
                case "rentDay" -> rentDay = Integer.parseInt(value);
                case "startDate" -> startDate = LocalDate.parse(value);
                case "monthlyTotal" -> monthly = new MonthlyAmount(new BigDecimal(value),
                    monthly.breakdown());
                default -> throw new IllegalArgumentException("Uncorrectable field: " + key);
            }
            // ACTIVE, not "has ever been active": before activation nothing was published.
            if (state == State.ACTIVE && PUBLISHED_TO_ACCOUNTING.contains(key)) {
                correctedAfterPublication.add(key);
            }
        });
    }

    /**
     * Refuses a caller who does not own this tenancy — asked of the tenancy rebuilt from its own
     * stream, as {@code Unit}, {@code Property} and {@code Repair} answer the same question.
     *
     * <p>A tenancy's workspace is inherited from its unit at reservation and never moves, so this
     * is the same answer the unit would give, one load closer to the decision. It replaces
     * {@code WorkspaceGuard.requireTenancy}, which asked pm_tenancy — a projection written by a
     * second statement after the append, and therefore allowed to lag the stream this is read from.
     */
    public void requireOwnedBy(UUID caller) {
        if (caller == null || workspaceId == null || !workspaceId.equals(caller)) {
            throw new UnknownInThisWorkspaceException("tenancy " + id);
        }
    }

    public State state() {
        return state;
    }

    /** Still only a reservation: nothing has started and it can still be cancelled. */
    public boolean isReserved() {
        return state == State.RESERVED;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID unitId() {
        return unitId;
    }

    public List<UUID> tenantContactIds() {
        return List.copyOf(tenantContactIds);
    }

    public List<UUID> guarantorContactIds() {
        return List.copyOf(guarantorContactIds);
    }

    public LocalDate startDate() {
        return startDate;
    }

    public LocalDate endDate() {
        return endDate;
    }

    public LegalForm legalForm() {
        return legalForm;
    }

    public MonthlyAmount monthly() {
        return monthly;
    }

    public int rentDay() {
        return rentDay;
    }

    public BigDecimal depositAmount() {
        return depositAmount;
    }

    public String paymentReference() {
        return paymentReference;
    }
}
