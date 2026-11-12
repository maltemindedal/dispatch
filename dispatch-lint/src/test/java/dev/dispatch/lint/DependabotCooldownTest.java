package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The dependabot-cooldown rule")
class DependabotCooldownTest {
    private static final String CONFIG = ".github/dependabot.yml";

    @TempDir
    Path root;

    private void write(String path, String text) throws IOException {
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), text);
    }

    @Test
    @DisplayName("reports each cooldown block in dependabot.yml and cites PR 15")
    void reportsACooldownBlock() throws IOException {
        write(CONFIG, "version: 2\nupdates:\n  - package-ecosystem: gradle\n    cooldown:\n      default-days: 7\n"
                + "  - package-ecosystem: github-actions\n    cooldown: {default-days: 7}\n");

        assertThat(TextRules.check(root, List.of(CONFIG)))
                .hasSize(2)
                .allSatisfy(finding -> assertThat(finding)
                        .contains("[dependabot-cooldown]", "PR #15", "Delete the `cooldown` block"));
        assertThat(TextRules.check(root, List.of(CONFIG))).first().asString().startsWith(CONFIG + ":4:");
    }

    @Test
    @DisplayName("accepts a dependabot.yml without a cooldown key, even one that names the word in a comment")
    void acceptsAFileWithoutTheKey() throws IOException {
        write(CONFIG, "# no cooldown here\nversion: 2\nupdates: []\n");

        assertThat(TextRules.check(root, List.of(CONFIG))).isEmpty();
    }

    @Test
    @DisplayName("looks only at dependabot.yml")
    void looksOnlyAtDependabotYml() throws IOException {
        write(".github/workflows/ci.yml", "cooldown: 5\n");

        assertThat(TextRules.check(root, List.of(".github/workflows/ci.yml"))).isEmpty();
    }
}
