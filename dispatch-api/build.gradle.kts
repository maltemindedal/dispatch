plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

description = "Spring Boot REST API in front of the queue engine."

dependencies {
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
