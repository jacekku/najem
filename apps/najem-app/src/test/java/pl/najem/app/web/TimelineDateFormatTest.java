package pl.najem.app.web;

import org.junit.jupiter.api.Test;
import pl.najem.reporting.application.TimelineQuery;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The formatter's own reading of itself, with nothing booted — the {@code LeadFormTest}/
 * {@code SearchGroupingTest} precedent for logic that a container adds nothing to.
 *
 * <p>Worth having on its own: an unasserted date format silently reverts to whatever
 * {@code LocalDate#toString()} happens to produce (ISO, {@code 2026-08-07}), and nothing else in
 * this suite renders a timeline entry with a fixed, known date to catch that.
 */
class TimelineDateFormatTest {

    @Test
    void anEntrysDateRendersAsDdMmYyyyNotIso() {
        var entries = List.of(new TimelineQuery.Entry(
            LocalDate.of(2026, 8, 7), "tenancy-reserved", "Reserved from 2026-09-01 (najem_okazjonalny)"));

        var rows = TimelineScreenController.format(entries);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.occurredOn()).isEqualTo("07.08.2026");
            // kind and summary pass through unchanged — this is a date formatter, not a rewrite.
            assertThat(row.kind()).isEqualTo("tenancy-reserved");
            assertThat(row.summary()).isEqualTo("Reserved from 2026-09-01 (najem_okazjonalny)");
        });
    }

    @Test
    void aSingleDigitDayAndMonthAreZeroPadded() {
        var entries = List.of(new TimelineQuery.Entry(
            LocalDate.of(2026, 1, 3), "tenancy-activated", "Tenancy started"));

        var rows = TimelineScreenController.format(entries);

        assertThat(rows).singleElement().extracting(TimelineScreenController.Row::occurredOn)
            .isEqualTo("03.01.2026");
    }
}
