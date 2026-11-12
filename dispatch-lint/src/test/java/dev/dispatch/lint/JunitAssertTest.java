package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The junit-assert rule")
class JunitAssertTest {
    @TempDir
    Path root;

    @Test
    @DisplayName("reports a JUnit assertion in test sources, imported statically or not")
    void reportsJunitAssertions() {
        assertThat(Fixture.compile(root, "test/Bad.java", """
                import static org.junit.jupiter.api.Assertions.assertThrows;

                import org.junit.jupiter.api.Assertions;

                class Bad {
                    void check() {
                        assertThrows(IllegalStateException.class, () -> { throw new IllegalStateException(); });
                        Assertions.assertTrue(true);
                    }
                }
                """)).containsExactly(
                "7:9: [junit-assert] Assertions.assertThrows is a JUnit assertion. Assert with AssertJ:"
                        + " `assertThat(...)`, or `assertThatThrownBy(...)` for an exception (AGENTS.md, Tests).",
                "8:19: [junit-assert] Assertions.assertTrue is a JUnit assertion. Assert with AssertJ:"
                        + " `assertThat(...)`, or `assertThatThrownBy(...)` for an exception (AGENTS.md, Tests).");
    }

    @Test
    @DisplayName("accepts AssertJ in test sources, and a JUnit assertion outside them")
    void acceptsAssertJAndOtherSources() {
        assertThat(Fixture.compile(root, "test/Good.java", """
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.assertj.core.api.Assertions.assertThatThrownBy;

                class Good {
                    void check() {
                        assertThat(1).isEqualTo(1);
                        assertThatThrownBy(() -> { throw new IllegalStateException(); })
                                .isInstanceOf(IllegalStateException.class);
                    }
                }
                """)).isEmpty();
        assertThat(Fixture.compile(root, "main/Other.java", """
                import org.junit.jupiter.api.Assertions;

                class Other {
                    void check() {
                        Assertions.assertTrue(true);
                    }
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("accepts module-info.java and package-info.java in test sources")
    void coversModuleAndPackageDeclarations() {
        Fixture.writeDeclarations(root, "test", "");

        assertThat(Fixture.compile(root)).isEmpty();
    }
}
