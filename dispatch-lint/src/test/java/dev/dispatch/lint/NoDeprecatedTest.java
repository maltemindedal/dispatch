package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The no-deprecated rule")
class NoDeprecatedTest {
    @TempDir
    Path root;

    @Test
    @DisplayName("reports @Deprecated on a method, a field and a class in main sources, with the fix")
    void reportsDeprecated() {
        assertThat(Fixture.compile(root, "main/Bad.java", """
                class Bad {
                    @Deprecated
                    int field;

                    @Deprecated(since = "1.2")
                    void method() {}

                    @Deprecated
                    static class Nested {}
                }
                """)).containsExactly(
                "2:5: [no-deprecated] field is marked @Deprecated, but nothing publishes this code, so no"
                        + " caller needs time to move. Migrate the callers and delete it in the same change"
                        + " (AGENTS.md, Engine code).",
                "5:5: [no-deprecated] method is marked @Deprecated, but nothing publishes this code, so no"
                        + " caller needs time to move. Migrate the callers and delete it in the same change"
                        + " (AGENTS.md, Engine code).",
                "8:5: [no-deprecated] Nested is marked @Deprecated, but nothing publishes this code, so no"
                        + " caller needs time to move. Migrate the callers and delete it in the same change"
                        + " (AGENTS.md, Engine code).");
    }

    @Test
    @DisplayName("accepts code that is not deprecated, and @Deprecated in test sources")
    void acceptsOtherCode() {
        assertThat(Fixture.compile(root, "main/Good.java", """
                class Good {
                    void method() {}
                }
                """)).isEmpty();
        assertThat(Fixture.compile(root, "test/Spec.java", """
                class Spec {
                    @Deprecated
                    void method() {}
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("reports @Deprecated on a package or module declaration in main sources")
    void coversModuleAndPackageDeclarations() {
        Fixture.writeDeclarations(root, "main", "@Deprecated");

        assertThat(Fixture.compile(root)).extracting(error -> error.substring(0, error.indexOf(" is marked")))
                .containsExactlyInAnyOrder(
                        "package-info.java 1:1: [no-deprecated] app",
                        "module-info.java 1:1: [no-deprecated] app");
    }
}
