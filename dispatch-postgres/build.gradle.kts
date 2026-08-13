plugins {
    `java-library`
}

description = "JDBC-backed JobStore (PostgreSQL / H2). Still no Spring."

dependencies {
    api(project(":dispatch-core"))

    // Drivers stay out of the API surface: the caller supplies a configured DataSource.
    compileOnly(rootProject.libs.postgresql)
    compileOnly(rootProject.libs.h2)

    testImplementation(testFixtures(project(":dispatch-core")))
    testImplementation(rootProject.libs.postgresql)
    testImplementation(rootProject.libs.h2)
    testImplementation(rootProject.libs.hikaricp)
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
    testRuntimeOnly(rootProject.libs.logback.classic)
}
