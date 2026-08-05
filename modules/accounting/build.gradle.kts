// java-test-fixtures carries PostgresAccounting: the hand wiring of services onto a JdbcTemplate,
// which only tests need and which must not ship in the jar. Reporting's tripwires drive accounting's
// real services and need the same wiring, and a fixtures source set is how one module's tests lend
// it to another's without either putting it in production code.
plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    // The fixtures assemble the adapters, so they need what the adapters are built on. Test
    // fixtures inherit the module's api, not its implementation dependencies.
    testFixturesImplementation(project(":platform:eventstore"))
    testFixturesImplementation("org.springframework:spring-jdbc")

    api(project(":contracts"))
    implementation(project(":platform:eventstore"))
    implementation(project(":platform:mt940"))
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
