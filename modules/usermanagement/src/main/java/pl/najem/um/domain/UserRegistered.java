package pl.najem.um.domain;

import java.time.LocalDate;
import java.util.UUID;

public record UserRegistered(UUID userId, UUID keycloakSubject, LocalDate registeredOn) {}
