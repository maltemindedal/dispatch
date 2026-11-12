package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The em-dash rule")
class EmDashTest {
    private static final String FINDING = ": error: [em-dash] An em dash. Write two short sentences "
            + "instead (AGENTS.md, Prose, commits and PRs).";

    private static final String DASH = Character.toString(0x2014);

    @TempDir
    Path root;

    @Test
    @DisplayName("reports the line of an em dash in a Markdown file")
    void reportsAMarkdownEmDash() throws IOException {
        Files.writeString(root.resolve("a.md"), "fine\nfast " + DASH + " loose\n");

        assertThat(TextRules.check(root, List.of("a.md"))).containsExactly("a.md:2" + FINDING);
    }

    @Test
    @DisplayName("reports an em dash in a Java comment and in a string")
    void reportsAnEmDashInJava() throws IOException {
        Files.writeString(
                root.resolve("A.java"), "// a " + DASH + " b\nclass A { String s = \"c " + DASH + " d\"; }\n");

        assertThat(TextRules.check(root, List.of("A.java"))).containsExactly("A.java:1" + FINDING, "A.java:2" + FINDING);
    }

    @Test
    @DisplayName("accepts a hyphen and an en dash")
    void acceptsOtherDashes() throws IOException {
        Files.writeString(root.resolve("a.md"), "a - b and 2020" + Character.toString(0x2013) + "2024\n");

        assertThat(TextRules.check(root, List.of("a.md"))).isEmpty();
    }

    @Test
    @DisplayName("skips a file that is not text")
    void skipsABinaryFile() throws IOException {
        Files.write(root.resolve("a.jar"), new byte[] {(byte) 0xE2, (byte) 0x80, (byte) 0x94, (byte) 0xFF});

        assertThat(TextRules.check(root, List.of("a.jar"))).isEmpty();
    }
}
