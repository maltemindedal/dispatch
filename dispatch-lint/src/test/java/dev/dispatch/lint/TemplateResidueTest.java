package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("The template-residue rule")
class TemplateResidueTest {
    @TempDir
    Path root;

    @Test
    @DisplayName("reports the NOTE placeholder a code of conduct template ships with")
    void reportsTheCodeOfConductNote() throws IOException {
        Files.writeString(root.resolve("CODE_OF_CONDUCT.md"),
                "ok\n**[NOTE: The remedies below are suggestions. Edit this section.]**\n");

        assertThat(TextRules.check(root, List.of("CODE_OF_CONDUCT.md"))).containsExactly(
                "CODE_OF_CONDUCT.md:2: error: [template-residue] `[NOTE:` is placeholder text left over from a "
                        + "template. Write the real content, or delete the line if it does not apply to this project.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[INSERT CONTACT METHOD]", "[DESCRIBE THE STEPS]", "TODO(placeholder) fill in"})
    @DisplayName("reports an INSERT, a DESCRIBE and a TODO(placeholder) in Markdown and YAML")
    void reportsOtherPlaceholders(String line) throws IOException {
        Files.writeString(root.resolve("a.md"), line + "\n");
        Files.writeString(root.resolve("a.yml"), "key: " + line + "\n");

        assertThat(TextRules.check(root, List.of("a.md", "a.yml"))).hasSize(2);
    }

    @Test
    @DisplayName("accepts a link, a checkbox and a note that is not a placeholder")
    void acceptsOrdinaryBrackets() throws IOException {
        Files.writeString(root.resolve("a.md"), "- [ ] done\n[docs](docs/a.md)\nNOTE: read this\n[Note] later\n");

        assertThat(TextRules.check(root, List.of("a.md"))).isEmpty();
    }

    @Test
    @DisplayName("leaves Java source alone")
    void leavesJavaAlone() throws IOException {
        Files.writeString(root.resolve("A.java"), "// [NOTE: kept]\nclass A {}\n");

        assertThat(TextRules.check(root, List.of("A.java"))).isEmpty();
    }
}
