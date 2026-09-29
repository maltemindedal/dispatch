plugins {
    `java-library`
}

description = "JDBC-backed JobStore (PostgreSQL / H2). Still no Spring."

dependencies {
    api(project(":dispatch-core"))

    // No JDBC driver here: the adapter speaks only java.sql, and the caller supplies a configured
    // DataSource (and with it, the driver).

    testImplementation(testFixtures(project(":dispatch-core")))
    testImplementation(rootProject.libs.postgresql)
    testImplementation(rootProject.libs.h2)
    testImplementation(rootProject.libs.hikaricp)
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
    testRuntimeOnly(rootProject.libs.logback.classic)
}
