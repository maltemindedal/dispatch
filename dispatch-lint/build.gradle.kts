description = "The repo rules from AGENTS.md: a javac plugin for per-file rules, checkRepo for whole-repo rules."

val ruled = rootProject.subprojects - project
val checkRepo = tasks.register<JavaExec>("checkRepo") {
    group = "verification"
    description = "Runs the whole-repo rules over the sources and the indexes the javac plugin wrote."
    val root = rootDir
    val sources = ruled.map { it.file("src") }
    val indexes = ruled.map { it.layout.buildDirectory.dir("dispatch-lint").get().asFile }
    val marker = layout.buildDirectory.file("checkRepo.ok")
    dependsOn(ruled.map { it.tasks.withType<JavaCompile>() })
    inputs.files(sources.map { fileTree(it) { include("**/*.java") } })
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sources")
    inputs.files(indexes.map { fileTree(it) })
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("indexes")
    outputs.file(marker)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.dispatch.lint.RepoCheck"
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(root.path, sources.joinToString(File.pathSeparator), indexes.joinToString(File.pathSeparator))
    })
    doLast { marker.get().asFile.writeText("ok") }
}
tasks.check { dependsOn(checkRepo) }
