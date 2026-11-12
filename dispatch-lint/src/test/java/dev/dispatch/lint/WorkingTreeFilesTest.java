package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The file list of checkRepo")
class WorkingTreeFilesTest {
    private static final String DASH = Character.toString(0x2014);

    @TempDir
    Path root;

    @BeforeEach
    void commitTwoFiles() throws IOException, InterruptedException {
        Files.writeString(root.resolve("gone.md"), "fine\n");
        Files.writeString(root.resolve("moved.md"), "a " + DASH + " b\n");
        git("init", "-q");
        git("add", ".");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
    }

    @Test
    @DisplayName("passes when a tracked file is deleted without staging")
    void passesWhenATrackedFileIsDeleted() throws IOException {
        Files.delete(root.resolve("gone.md"));
        Files.writeString(root.resolve("moved.md"), "fine\n");

        assertThat(TextRules.check(root, RepoCheck.trackedFiles(root))).isEmpty();
    }

    @Test
    @DisplayName("reports an em dash in a moved file at its new path")
    void reportsAMovedFileAtItsNewPath() throws IOException {
        Files.move(root.resolve("moved.md"), root.resolve("renamed.md"));

        assertThat(TextRules.check(root, RepoCheck.trackedFiles(root)))
                .singleElement()
                .asString()
                .startsWith("renamed.md:1: error: [em-dash]");
    }

    private void git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start();
        assertThat(process.waitFor()).isZero();
    }
}
