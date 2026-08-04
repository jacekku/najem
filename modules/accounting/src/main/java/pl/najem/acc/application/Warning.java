package pl.najem.acc.application;

import pl.najem.acc.domain.WarningKind;

import java.util.UUID;

/** A raised compliance flag. Never blocks the posting — this is an expert system, not a gate. */
public record Warning(UUID warningId, UUID tenancyId, WarningKind kind, String detail) {

    public static Warning of(WarningKind kind, String detail) {
        return new Warning(null, null, kind, detail);
    }
}
