plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "Spring Boot REST API in front of the queue engine."

dependencies {
    // Boot's dependency versions, applied as a plain Gradle platform. Constraints only raise a
    // version, so a version the catalog or another module asks for still wins when it is higher.
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    // Two libraries the Boot 4.1.1 BOM manages carry published advisories that a newer patch
    // release inside the same line fixes. Each pin below can go once the Boot BOM manages a
    // version at least this new (Gradle keeps the higher of the two, so a stale pin cannot hold
    // anything back).
    //   Tomcat 11.0.24 -> 11.0.26: GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6, GHSA-h3x4-894j-xpx5
    //   tools.jackson jackson-databind 3.1.5 -> 3.1.7: GHSA-gx83-3vf8-gh7j, GHSA-q4xh-88c3-wmh7,
    //     GHSA-wjgm-6hv5-3cvf
    // Neither is reachable from this application's own code, but a vulnerable library on the
    // classpath is not something to argue about per scanner report.
    implementation(platform(rootProject.libs.jackson.bom))
    constraints {
        val tomcat = rootProject.libs.versions.tomcat.get()
        implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcat")
        implementation("org.apache.tomcat.embed:tomcat-embed-el:$tomcat")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:$tomcat")
    }

    implementation(project(":dispatch-core"))
    implementation(project(":dispatch-postgres"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    runtimeOnly(rootProject.libs.postgresql)
    runtimeOnly(rootProject.libs.h2)
    // Boot 4 moved the H2 console out of the core auto-configuration into its own module.
    runtimeOnly("org.springframework.boot:spring-boot-h2console")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 split the web test support out of spring-boot-starter-test: MockMvc's auto-configuration
    // and TestRestTemplate each have their own module now, and TestRestTemplate needs the
    // RestTemplate support that only the restclient starter brings.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient")
    testImplementation(rootProject.libs.testcontainers.junit)
    testImplementation(rootProject.libs.testcontainers.postgresql)
}
