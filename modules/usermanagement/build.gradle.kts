plugins {
    `java-library`
    `java-test-fixtures`
}

// The Keycloak Testcontainers suite pulls and boots a real identity provider: minutes, against
// seconds for everything else in the repo. Every agent now runs a full build two or three times per
// merge cycle (rebase discipline), so it is opt-in locally and always on in CI. That opt-in is
// configured in the root build alongside the `integration` tag, because a module-level
// `useJUnitPlatform { }` replaces the root's filter rather than adding to it — which is how one of
// the two tags would quietly stop being excluded.

dependencies {
    api(project(":contracts"))
    implementation(project(":platform:eventstore"))
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // oauth2Login lives here because SecurityConfig does: this module owns the security posture,
    // and the browser chain is one of the postures rather than a UI concern.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")

    testFixturesImplementation(project(":platform:eventstore"))
    testFixturesImplementation("org.springframework:spring-jdbc")

    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
