package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The handler-api rule")
class HandlerApiTest {
    private static final String FIX = ". JobContext is the handler API, and the table under `What the context"
            + " gives you` in docs/guides/writing-a-handler.md documents it. Make the two agree: add or fix the"
            + " table row, or delete the method if no handler needs it.";

    @TempDir
    Path root;

    @Test
    @DisplayName("accepts a guide table that lists every public method of JobContext")
    void acceptsATableInStep() throws IOException {
        jobContext("public String payload() { return \"\"; }  public boolean leaseExpired(long now) { return true; }");
        guide("| `payload()` | The JSON. |", "| `jobId()`, `leaseExpired(now)` | A call such as `other()`. |");

        assertThat(findings()).isEmpty();
    }

    @Test
    @DisplayName("reports a public method of JobContext the table does not list")
    void reportsAnUndocumentedMethod() throws IOException {
        jobContext("public String payload() { return \"\"; }");
        guide("| `jobId()` | Identity. |");

        assertThat(findings()).containsExactly(
                "src/main/dev/dispatch/core/handler/JobContext.java:9: error: [handler-api] JobContext.payload() is "
                        + "public, and the table does not list it" + FIX);
    }

    @Test
    @DisplayName("reports a table row for a method JobContext does not have")
    void reportsAStaleRow() throws IOException {
        jobContext("");
        guide("| `jobId()` | Identity. |", "| `deadline()` | Gone. |");

        assertThat(findings()).containsExactly("docs/guides/writing-a-handler.md:6: error: [handler-api] The table "
                + "lists `deadline()`, and JobContext has no public method of that name" + FIX);
    }

    @Test
    @DisplayName("leaves the constructor, toString() and non-public methods out of the comparison")
    void comparesPublicMethodsOnly() throws IOException {
        jobContext("public JobContext() {}  @Override public String toString() { return \"\"; }  void internal() {}"
                + "  static void use(JobContext context) { context.internal(); use(new JobContext()); }");
        guide("| `jobId()` | Identity. |");

        assertThat(findings()).isEmpty();
    }

    private void jobContext(String members) {
        Fixture.write(root, "main/dev/dispatch/core/handler/JobContext.java", """
                package dev.dispatch.core.handler;

                public final class JobContext {
                    public String jobId() {
                        return "";
                    }

                    // members
                    %s
                }
                """.formatted(members));
    }

    private void guide(String... rows) throws IOException {
        Path guide = root.resolve("docs/guides/writing-a-handler.md");
        Files.createDirectories(guide.getParent());
        Files.writeString(guide, "## What the context gives you\n\n| Method | Meaning |\n| --- | --- |\n"
                + String.join("\n", rows) + "\n");
    }

    private List<String> findings() throws IOException {
        assertThat(Fixture.compile(root)).isEmpty();
        return RepoCheck.handlerApiTheGuideMisstates(root, List.of(Fixture.indexDir(root)));
    }
}
