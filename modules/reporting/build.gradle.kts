plugins { `java-library` }

// Reporting is a read-side and depends on NO other module, by ruling (najem-build seq 65 pt 2).
// It reads the shared `events` table as event_type + JsonNode, never another module's types.
// If a dependency on modules:* ever appears here, the allowlist ruling has been broken.
dependencies {
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Runtime-only and test-only, on purpose: tests need the `events` table's migration on the
    // classpath, but Reporting's production code must never touch the event store's Java types.
    // A compile-scope dependency here would let a projection deserialize a stored event into
    // another module's record class, which is exactly what the allowlist ruling forbids.
    testRuntimeOnly(project(":platform:eventstore"))

    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
