plugins { `java-library` }

dependencies {
    testImplementation(project(":apps:najem-app"))
    testImplementation(project(":apps:fakebank"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("org.awaitility:awaitility:4.2.2")
}
