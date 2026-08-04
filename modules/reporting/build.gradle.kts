plugins { `java-library` }

// Reporting is a read-side and depends on NO other module, by ruling (najem-build seq 65 pt 2).
// It reads the shared `events` table as event_type + JsonNode, never another module's types.
// No PRODUCTION dependency on any module -- if one ever appears below, the allowlist ruling has
// been broken. The two test-scope module deps are the single sanctioned exception; see the note
// on them before touching either.
dependencies {
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Test scope only, on purpose. Tests need the `events` migration and, for the tripwires, the
    // event store itself to drive other modules' services. Reporting's PRODUCTION code must never
    // touch the event store's Java types: it reads payloads as JsonNode, never as a record class.
    testImplementation(project(":platform:eventstore"))

    // THE ONE SANCTIONED `modules:*` DEPENDENCY (najem-build seq 91), and it is TEST SCOPE ONLY.
    // EventContractTest drives PM's and accounting's real services so the tripwires assert against
    // what those modules actually emit. Golden JSON fixtures cannot do that -- they keep passing
    // after the module renames the field they imitate, which is the only event a tripwire exists
    // to catch. DO NOT promote these to implementation/api, and do not add others: a projection
    // importing another module's record class is the allowlist ruling actually breaking.
    testImplementation(project(":modules:propertymanagement"))
    testImplementation(project(":modules:accounting"))
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
