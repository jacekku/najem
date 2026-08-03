package pl.najem.pm.domain;

import java.time.LocalDate;
import java.util.UUID;

public record TenancyActivated(UUID tenancyId, LocalDate activatedOn) {
}
