plugins {
    `java-library`
    // Publishes the shared JobStore contract test suite so dispatch-postgres can reuse it.
    `java-test-fixtures`
}

description = "Queue engine: plain Java, no Spring, no external broker."

dependencies {
    // The engine depends only on the JDK plus a logging facade.
    api(rootProject.libs.slf4j.api)

    testFixturesApi(rootProject.libs.junit.jupiter)
    testFixturesApi(rootProject.libs.assertj)

    testRuntimeOnly(rootProject.libs.logback.classic)
}

tasks.test {
    // LifecycleDocTest reads the doc, so a change to it must rerun the test rather than replay it.
    inputs.file(rootProject.file("docs/architecture/job-lifecycle.md"))
        .withPropertyName("lifecycleDoc")
}
