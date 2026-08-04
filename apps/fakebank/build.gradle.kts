plugins {
    java
    id("org.springframework.boot") version "3.3.5"
}

dependencies {
    implementation(project(":platform:mt940"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
