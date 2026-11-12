plugins {
    java
    alias(libs.plugins.spring.boot) apply false
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
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
    }

    if (name != "dispatch-lint") {
        // Every compile task runs the dispatch-lint javac plugin and writes the index of the
        // sources it saw. :dispatch-lint:checkRepo fails for a source file no index vouches for.
        val root = rootDir.path
        configurations.matching { it.name.endsWith("nnotationProcessor") }.configureEach {
            dependencies.add(project.dependencies.project(":dispatch-lint"))
        }
        tasks.withType<JavaCompile>().configureEach {
            // The index sits in an output directory that every compile empties first, with or
            // without the plugin. So a compile that lost this wiring leaves no index behind.
            val index = options.generatedSourceOutputDirectory.file("dispatch-lint.idx")
            // An incremental compile shows the plugin only the changed files, so the index
            // would lose the rest.
            options.isIncremental = false
            options.compilerArgumentProviders.add(CommandLineArgumentProvider {
                listOf("-Xplugin:DispatchLint $root ${index.get().asFile.path}")
            })
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
