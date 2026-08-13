plugins {
    // Lets Gradle download a JDK 21 toolchain automatically if one is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "dispatch"

include("dispatch-core")
include("dispatch-postgres")
include("dispatch-api")
