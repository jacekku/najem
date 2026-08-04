package pl.najem.pm.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenancyTest {

    private final UUID tenancyId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private List<Object> reserved() {
        return Tenancy.reserve(tenancyId, workspaceId, unitId, LocalDate.of(2026, 9, 1),
            LocalDate.of(2027, 8, 31), new BigDecimal("2500"), "NAJEM/M1/2026");
    }

    @Test
    void reservedTenancyActivates() {
        var events = Tenancy.from(reserved()).activate(LocalDate.of(2026, 9, 1));

        assertThat(events).containsExactly(new TenancyActivated(tenancyId, LocalDate.of(2026, 9, 1)));
    }

    @Test
    void activatingTwiceIsRejected() {
        var history = new ArrayList<>(reserved());
        history.add(new TenancyActivated(tenancyId, LocalDate.of(2026, 9, 1)));

        assertThatThrownBy(() -> Tenancy.from(history).activate(LocalDate.of(2026, 9, 2)))
            .isInstanceOf(IllegalStateException.class);
    }
}
