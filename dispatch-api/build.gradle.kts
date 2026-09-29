plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "Spring Boot REST API in front of the queue engine."

dependencies {
    // Boot's dependency versions, applied as a plain Gradle platform. Constraints only raise a
    // version, so a version the catalog or another module asks for still wins when it is higher.
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    implementation(project(":dispatch-core"))
    implementation(project(":dispatch-postgres"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    runtimeOnly(rootProject.libs.postgresql)
    runtimeOnly(rootProject.libs.h2)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
}
