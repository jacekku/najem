plugins { java }

subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
    tasks.withType<Test> {
        useJUnitPlatform()
        // colima ships Docker 29 (min API 1.40); docker-java defaults to 1.32
        systemProperty("api.version", "1.44")
    }
    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:3.3.5"))
        "testImplementation"(platform("org.testcontainers:testcontainers-bom:1.21.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"("org.assertj:assertj-core:3.26.3")
    }
    // Test fixtures get the same managed versions as everything else. Without this a fixtures
    // source set asks for a Spring artifact with no version and the build fails at resolution,
    // because the platform above is declared on `implementation` and fixtures do not inherit it.
    plugins.withId("java-test-fixtures") {
        dependencies {
            "testFixturesImplementation"(
                platform("org.springframework.boot:spring-boot-dependencies:3.3.5"))
        }
    }
}
