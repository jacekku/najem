plugins {
    java
    id("org.springframework.boot") version "3.3.5"
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":platform:eventstore"))
    implementation(project(":modules:propertymanagement"))
    implementation(project(":modules:accounting"))
    implementation(project(":modules:usermanagement"))
    implementation(project(":modules:contacts"))
    implementation(project(":modules:reporting"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
