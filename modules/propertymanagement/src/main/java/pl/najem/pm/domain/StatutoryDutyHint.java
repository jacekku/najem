package pl.najem.pm.domain;

/**
 * Who art. 6a-6b suggests should bear a repair — a hint the manager records, never a verdict the
 * software reaches. The split between landlord duties (art. 6a: the building, installations,
 * making the flat fit to live in) and tenant duties (art. 6b: minor repairs and ordinary wear) is
 * argued over in practice, which is exactly why NEGOTIABLE is a real answer and not a missing one.
 */
public enum StatutoryDutyHint {

    LANDLORD,
    TENANT,
    NEGOTIABLE;

    public String wireName() {
        return name().toLowerCase();
    }
}
