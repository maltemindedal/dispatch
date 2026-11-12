package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The lint-ran rule")
class RepoCheckTest {
    private static final String FINDING = "src/Seen.java: error: [lint-ran] No clean run of the "
            + "dispatch-lint javac plugin has seen this file as it is now, so no rule vouches for it. Every "
            + "JavaCompile needs the plugin: restore the dispatch-lint wiring in the root build.gradle.kts, "
            + "or extend it to this module.";

    @TempDir
    Path root;

    @Test
    @DisplayName("accepts a source file the plugin saw")
    void acceptsASourceThePluginSaw() throws IOException {
        Fixture.compile(root, "Seen.java", "class Seen {}");

        assertThat(missed()).isEmpty();
    }

    @Test
    @DisplayName("reports a source file no index names")
    void reportsASourceNoIndexNames() throws IOException {
        Files.createDirectories(Fixture.sourceDir(root));
        Files.writeString(Fixture.sourceDir(root).resolve("Seen.java"), "class Seen {}");

        assertThat(missed()).containsExactly(FINDING);
    }

    @Test
    @DisplayName("reports a source file edited after the plugin last saw it")
    void reportsASourceEditedSinceTheIndex() throws IOException {
        Fixture.compile(root, "Seen.java", "class Seen {}");
        Files.writeString(Fixture.sourceDir(root).resolve("Seen.java"), "class Seen { int edited; }");

        assertThat(missed()).containsExactly(FINDING);
    }

    @Test
    @DisplayName("reports a source file whose last compile had a finding")
    void reportsASourceWithAFinding() throws IOException {
        Fixture.compile(root, "Seen.java", "class Seen {}");
        Fixture.compile(root, "Seen.java", "class Seen { java.time.Instant inline; }");

        assertThat(missed()).containsExactly(FINDING);
    }

    private List<String> missed() throws IOException {
        return RepoCheck.sourcesThePluginMissed(
                root, List.of(Fixture.sourceDir(root)), List.of(Fixture.indexDir(root)));
    }
}
