package pl.najem.pm.domain;

import java.time.LocalDate;

/** Fixed-term or indefinite. An indefinite tenancy occupies its unit until it is ended. */
public sealed interface Term {

    LocalDate endDate();

    record FixedTerm(LocalDate endDate) implements Term {
    }

    record Indefinite() implements Term {
        @Override
        public LocalDate endDate() {
            return null;
        }
    }
}
