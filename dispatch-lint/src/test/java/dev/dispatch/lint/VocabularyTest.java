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

@DisplayName("The vocabulary rule")
class VocabularyTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {
        "a worker will dequeue it", "the job is requeued", "the re-queue happens", "There is no reaper.",
        "revive the job", "reviving it", "the dead-letter queue", "a Dead Letter Queue", "dequeuing", "dequeueing", "REQUEUE"
    })
    @DisplayName("reports a banned word in prose, in any case and inflection")
    void reportsABannedWord(String line) throws IOException {
        Files.writeString(root.resolve("a.md"), "ok\n" + line + "\n");

        assertThat(TextRules.check(root, List.of("a.md"))).singleElement().asString().startsWith("a.md:2: error: [vocabulary] ");
    }

    @Test
    @DisplayName("reports a banned word inside a camel case identifier and a snake case one")
    void reportsABannedWordInAnIdentifier() throws IOException {
        Files.writeString(root.resolve("A.java"), "class A { void reclaimRequeues() {} int dead_letter_queue; }\n");

        assertThat(TextRules.check(root, List.of("A.java")))
                .extracting(finding -> finding.substring(finding.indexOf('`')))
                .allMatch(text -> text.startsWith("`requeues`") || text.startsWith("`dead letter queue`"))
                .hasSize(2);
    }

    @Test
    @DisplayName("names the words the project uses instead")
    void namesTheFix() throws IOException {
        Files.writeString(root.resolve("a.md"), "no reaper\n");

        assertThat(TextRules.check(root, List.of("a.md"))).containsExactly(
                "a.md:1: error: [vocabulary] `reaper` is not this project's word. Use claim (not dequeue), sweep "
                        + "(not reaper), dead letter (not dead-letter queue), manual retry (not revive or requeue), "
                        + "lease and selection (AGENTS.md, Prose, commits and PRs).");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ReentrantLock and locked_until", "JobFilter", "survive and revival", "the dead letter", "a queue",
        "reapply it"
    })
    @DisplayName("accepts lock, filter and words that only contain a banned stem")
    void acceptsOtherWords(String line) throws IOException {
        Files.writeString(root.resolve("a.md"), line + "\n");

        assertThat(TextRules.check(root, List.of("a.md"))).isEmpty();
    }

    @Test
    @DisplayName("exempts the rule's own source and tests, which name the words")
    void exemptsTheLintSources() throws IOException {
        Files.createDirectories(root.resolve("dispatch-lint/src"));
        Files.writeString(root.resolve("dispatch-lint/src/Rule.java"), "// requeue reaper\n");

        assertThat(TextRules.check(root, List.of("dispatch-lint/src/Rule.java"))).isEmpty();
    }
}
