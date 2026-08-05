plugins { java }

subprojects {
    apply(plugin = "java")
    repositories { mavenCentral() }
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
    // Two tiers, and the tag is which one a test is in. Anything that boots a container is
    // @Tag("integration"): minutes for the suite, against seconds for everything else. The fast
    // tier is the inner loop while code is being changed; the slow tier is what a merge runs.
    //   ./gradlew build                      -> integration skipped
    //   ./gradlew build -PintegrationTests   -> included
    //   CI=true ./gradlew build              -> included
    //
    // The keycloak tag is the same arrangement one notch further out — it pulls and boots a real
    // identity provider — and opts in separately, so a run that wants the database suite does not
    // silently get that one too.
    tasks.withType<Test> {
        val ci = System.getenv("CI") != null
        val excluded = buildList {
            if (!(project.hasProperty("integrationTests") || ci)) add("integration")
            if (!(project.hasProperty("keycloakTests") || ci)) add("keycloak")
        }
        useJUnitPlatform {
            if (excluded.isNotEmpty()) {
                excludeTags(*excluded.toTypedArray())
            }
        }
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
