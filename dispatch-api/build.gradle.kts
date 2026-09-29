plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "Spring Boot REST API in front of the queue engine."

dependencies {
    // Boot's dependency versions, applied as a plain Gradle platform. Constraints only raise a
    // version, so a version the catalog or another module asks for still wins when it is higher.
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    // Boot 3.5 left open-source support on 2026-06-30, so its BOM no longer moves and three
    // libraries it manages carry published advisories. Each pin below is a patch release inside
    // the line Boot 3.5.16 ships, and each can go once the Boot BOM manages a version at least
    // this new (Gradle keeps the higher of the two, so a stale pin cannot hold anything back).
    //   Tomcat 10.1.55 -> 10.1.60: GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5
    //   jackson-databind 2.21.4 -> 2.21.7: GHSA-5gvw-p9qm-jgwh, GHSA-5jmj-h7xm-6q6v,
    //     GHSA-mhm7-754m-9p8w, GHSA-vvgp-rfg2-7rr6, GHSA-q4xh-88c3-wmh7, GHSA-wjgm-6hv5-3cvf,
    //     GHSA-gx83-3vf8-gh7j
    //   log4j-api / log4j-to-slf4j 2.24.3 -> 2.25.5: GHSA-qv9r-c865-cp47
    // None of them is reachable from this application's own code (see MODERNIZATION notes), but a
    // vulnerable library on the classpath is not something to argue about per scanner report.
    implementation(platform(rootProject.libs.jackson.bom))
    implementation(platform(rootProject.libs.log4j.bom))
    constraints {
        val tomcat = rootProject.libs.versions.tomcat.get()
        implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcat")
        implementation("org.apache.tomcat.embed:tomcat-embed-el:$tomcat")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:$tomcat")
    }

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
