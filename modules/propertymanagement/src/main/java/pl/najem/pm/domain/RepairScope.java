package pl.najem.pm.domain;

/** What a repair is attached to. Both are physical assets; neither is a tenancy. */
public enum RepairScope {

    PROPERTY,
    UNIT;

    public String wireName() {
        return name().toLowerCase();
    }
}
