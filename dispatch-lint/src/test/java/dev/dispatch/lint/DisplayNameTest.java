package dev.dispatch.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("The display-name rule")
class DisplayNameTest {
    @TempDir
    Path root;

    @Test
    @DisplayName("reports a test method and a test class without @DisplayName, and a name = that stands in for it")
    void reportsMissingDisplayNames() {
        assertThat(Fixture.compile(root, "test/Bad.java", """
                import org.junit.jupiter.api.RepeatedTest;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;

                class Bad {
                    @Test
                    void plain() {}

                    @RepeatedTest(2)
                    void repeated() {}

                    @ParameterizedTest(name = "{0} is fine")
                    @ValueSource(strings = {"a"})
                    void parameterized(String value) {}
                }
                """)).extracting(error -> error.substring(0, error.indexOf(" has no")))
                .containsExactly(
                        "6:1: [display-name] Test class Bad",
                        "8:10: [display-name] Test method plain",
                        "11:10: [display-name] Test method repeated",
                        "15:10: [display-name] Test method parameterized");
    }

    @Test
    @DisplayName("names the fix and says a name = does not replace the @DisplayName")
    void namesTheFix() {
        assertThat(Fixture.compile(root, "test/Bad.java", """
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                @DisplayName("Bad")
                class Bad {
                    @Test
                    void plain() {}
                }
                """)).containsExactly(
                "7:10: [display-name] Test method plain has no @DisplayName. Add `@DisplayName(\"<one sentence"
                        + " saying what it checks>\")`. A `name =` on @ParameterizedTest is extra, not a"
                        + " replacement (AGENTS.md, Tests).");
    }

    @Test
    @DisplayName("reports a concrete class that inherits tests from an abstract base")
    void reportsAnInheritingClass() {
        assertThat(Fixture.compile(root, "test/Inherits.java", """
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;

                @DisplayName("Base")
                abstract class Base {
                    @Test
                    @DisplayName("does a thing")
                    void thing() {}
                }

                class Inherits extends Base {}
                """)).extracting(error -> error.substring(0, error.indexOf(" has no")))
                .containsExactly("11:1: [display-name] Test class Inherits");
    }

    @Test
    @DisplayName("accepts named tests, an unnamed abstract base, a helper class and a named parameterized test")
    void acceptsNamedTestsAndExemptClasses() {
        assertThat(Fixture.compile(root, "test/Good.java", """
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;

                abstract class Base {
                    @Test
                    @DisplayName("does a thing")
                    void thing() {}
                }

                @DisplayName("Good")
                class Good extends Base {
                    @ParameterizedTest(name = "{0} is fine")
                    @ValueSource(strings = {"a"})
                    @DisplayName("a value is fine")
                    void parameterized(String value) {}
                }

                class Helper {
                    void notATest() {}
                }
                """)).isEmpty();
    }

    @Test
    @DisplayName("ignores main sources")
    void ignoresMainSources() {
        assertThat(Fixture.compile(root, "main/Other.java", """
                import org.junit.jupiter.api.Test;

                class Other {
                    @Test
                    void plain() {}
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
