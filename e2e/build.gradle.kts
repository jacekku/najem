plugins { `java-library` }

dependencies {
    testImplementation(project(":apps:najem-app"))
    testImplementation(project(":modules:usermanagement"))
    testImplementation(project(":apps:fakebank"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    // AppStandsTest asks the running application's own JdbcTemplate whether the migrations landed.
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("org.awaitility:awaitility:4.2.2")
}
