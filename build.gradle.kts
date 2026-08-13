plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "dev.dispatch"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testImplementation"(rootProject.libs.awaitility)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        // Tests exercise virtual threads and timing-sensitive queue behaviour.
        systemProperty("java.util.logging.manager", "java.util.logging.LogManager")
    }
}
